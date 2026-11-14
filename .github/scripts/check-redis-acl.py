"""Exercise the chart's real Redis/Sentinel ACLs; never print generated credentials."""

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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("chart", type=Path)
    parser.add_argument("--render-only", action="store_true", help="Validate rendered secrets without Docker")
    options = parser.parse_args()
    values = yaml.safe_load((options.chart / "values.yaml").read_text(encoding="utf-8"))
    rendered = run(["helm", "template", "acl-smoke", str(options.chart),
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
    if options.render_only:
        print("Rendered Redis/Sentinel credentials validated; Docker checks not executed.")
        return

    image = values["redis"]["image"]
    image = f"{image['registry']}/{image['repository']}:{image['tag']}"
    user = auth["REDIS_USERNAME"]
    group = values["redis"]["haMode"]["masterGroupName"]
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
        run(["docker", "pull", image])
        with tempfile.TemporaryDirectory(prefix="ddb-acl-") as directory:
            for index, config in enumerate(configs):
                name = "ddb-acl-" + uuid.uuid4().hex
                path = Path(directory) / f"{index}.conf"
                prefix = ("port 6379\nsave \"\"\nappendonly no\n" if index == 0 else
                          f"port 26379\nsentinel monitor {group} 127.0.0.1 1 2\n")
                path.write_text("bind 127.0.0.1\n" + prefix + config, encoding="utf-8")
                containers.append(name)
                run(["docker", "create", "--name", name, "--network", "none", image,
                     "redis-server", "/data/acl-test.conf", *(["--sentinel"] if index else [])])
                run(["docker", "cp", str(path), name + ":/data/acl-test.conf"])
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
            if cli(1, sentinel, "SENTINEL", "get-master-addr-by-name", group) != "127.0.0.1\n1":
                raise RuntimeError("Sentinel discovery failed")
            cli(1, sentinel, "SENTINEL", "master", group)
            for command in (("SENTINEL", "MONITOR", "forbidden", "127.0.0.1", "2", "2"),
                            ("SENTINEL", "FAILOVER", group), ("SENTINEL", "RESET", "*")):
                cli(1, sentinel, *command, denied="NOPERM")
            cli(1, sentinel, "-x", "AUTH", "sentinel-admin", denied="WRONGPASS", stdin=sentinel)
            cli(1, peer, "PING", identity="sentinel-admin")
        print("Redis/Sentinel ACL smoke passed: client commands allowed, administration and peer impersonation denied.")
    finally:
        for name in containers:
            subprocess.run(["docker", "rm", "-f", "-v", name], capture_output=True, timeout=30)


if __name__ == "__main__":
    main()
