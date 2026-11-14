"""Exercise standalone and HA Redis ACLs; never print generated credentials."""

import argparse
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

import yaml


def run(arguments, allow_error=False, timeout=120, **kwargs):
    try:
        result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout, **kwargs)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError("Could not execute the ACL smoke-test command") from None
    if result.returncode and not allow_error:
        raise RuntimeError("ACL smoke-test infrastructure command failed")
    return (result.stdout + result.stderr).strip()


def render(chart, values, ha):
    rendered = run(["helm", "template", "acl-smoke", str(chart),
                    "--set", f"redis.haMode.enabled={str(ha).lower()}",
                    "--show-only", "templates/secrets.yaml"])
    secrets = {item["metadata"]["name"]: item["data"] for item in yaml.safe_load_all(rendered) if item}
    auth = {key: base64.b64decode(value, validate=True).decode()
            for key, value in secrets[values["redis"]["auth"]["secretName"]].items()}
    passwords = [auth[key] for key in ("REDIS_PASSWORD", "REDIS_REPLICATION_PASSWORD",
                                     "REDIS_SENTINEL_PASSWORD", "REDIS_SENTINEL_PEER_PASSWORD")]
    if len(set(passwords)) != 4 or any(len(password) != 48 for password in passwords):
        raise RuntimeError("Redis application and peer credentials must be distinct")
    configs = [base64.b64decode(secrets[values["redis"][key]]["authentication.conf"], validate=True).decode()
               for key in ("extraSecretRedisConfigs", "extraSecretSentinelConfigs")]
    return auth, passwords, configs, ha


def smoke(auth, passwords, configs, ha, image, group, probe):
    user = auth["REDIS_USERNAME"]
    containers = []

    def cli(index, password, *command, identity=user, denied=None, stdin=None):
        environment = dict(os.environ, REDISCLI_AUTH=password)
        arguments = ["docker", "exec", "-i", "-e", "REDISCLI_AUTH", containers[index],
                     "redis-cli", "--raw", "-p", str((6379, 26379)[index]), "--user", identity, *command]
        output = run(arguments, allow_error=denied is not None, timeout=10, env=environment, input=stdin)
        if denied:
            if denied not in output:
                raise RuntimeError("A privileged Redis/Sentinel operation was not denied")
        elif any(error in output for error in ("NOPERM", "NOAUTH", "WRONGPASS", "ERR ")):
            raise RuntimeError("A required Redis/Sentinel client operation failed")
        return output

    try:
        with tempfile.TemporaryDirectory(prefix="ddb-acl-") as directory:
            conf = Path(directory) / "conf"
            conf.mkdir()
            # Both files are present: the probe must identify its own server process.
            for index, config in enumerate(configs):
                prefix = ("port 6379\nsave \"\"\nappendonly no\n" if index == 0 else
                          f"port 26379\nsentinel monitor {group} 127.0.0.1 1 2\n")
                (conf / ("redis.conf", "sentinel.conf")[index]).write_text(
                    "bind 127.0.0.1\n" + prefix + config, encoding="utf-8")
            for index in range(2 if ha else 1):
                name = "ddb-acl-" + uuid.uuid4().hex
                path = "/data/conf/" + ("redis.conf", "sentinel.conf")[index]
                containers.append(name)
                run(["docker", "create", "--name", name, "--network", "none", image,
                     "redis-server", path, *(["--sentinel"] if index else [])])
                run(["docker", "cp", str(conf), name + ":/data/conf"])
                run(["docker", "start", name])
                for attempt in range(50):
                    try:
                        if cli(index, passwords[index * 2], "PING") == "PONG":
                            break
                    except RuntimeError:
                        pass
                    time.sleep(0.2)
                else:
                    raise RuntimeError("Redis/Sentinel did not become ready")
                run(["docker", "exec", name, *probe], timeout=10)

            app, replication, sentinel, peer = passwords
            if cli(0, app, "SET", "acl-smoke", "value") != "OK" or cli(0, app, "GET", "acl-smoke") != "value":
                raise RuntimeError("Redis key access failed")
            if cli(0, app, "EVAL", "return redis.call('GET', KEYS[1])", "1", "acl-smoke") != "value":
                raise RuntimeError("Redis script access failed")
            if "# Server" not in cli(0, app, "INFO"):
                raise RuntimeError("Redis status access failed")
            for command in (("CONFIG", "GET", "*"), ("FLUSHALL",), ("ACL", "LIST"),
                            ("PUBLISH", "__sentinel__:hello", "forged")):
                cli(0, app, *command, denied="NOPERM")
            cli(0, app, "-x", "AUTH", "replication", denied="WRONGPASS", stdin=app)
            cli(0, replication, "PING", identity="replication")
            if ha:
                if cli(1, sentinel, "SENTINEL", "get-master-addr-by-name", group) != "127.0.0.1\n1":
                    raise RuntimeError("Sentinel discovery failed")
                cli(1, sentinel, "SENTINEL", "master", group)
                for command in (("SENTINEL", "MONITOR", "forbidden", "127.0.0.1", "2", "2"),
                                ("SENTINEL", "FAILOVER", group), ("SENTINEL", "RESET", "*")):
                    cli(1, sentinel, *command, denied="NOPERM")
                cli(1, sentinel, "-x", "AUTH", "sentinel-admin", denied="WRONGPASS", stdin=sentinel)
                cli(1, peer, "PING", identity="sentinel-admin")
    finally:
        for name in containers:
            subprocess.run(["docker", "rm", "-f", "-v", name], capture_output=True, timeout=30)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("chart", type=Path)
    parser.add_argument("--render-only", action="store_true", help="Validate rendered secrets without Docker")
    options = parser.parse_args()
    values = yaml.safe_load((options.chart / "values.yaml").read_text(encoding="utf-8"))
    modes = [render(options.chart, values, ha) for ha in (False, True)]
    if options.render_only:
        print("Rendered standalone/HA Redis credentials validated; Docker checks not executed.")
        return
    image = values["redis"]["image"]
    image = f"{image['registry']}/{image['repository']}:{image['tag']}"
    run(["docker", "pull", image])
    for mode in modes:
        smoke(*mode, image, values["redis"]["haMode"]["masterGroupName"],
              values["redis"]["customStartupProbe"]["exec"]["command"])
    print("Standalone/HA Redis ACL smoke passed: client commands allowed, administration and peer impersonation denied.")


if __name__ == "__main__":
    main()
