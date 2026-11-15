"""Render all supported workloads and verify VPA/image-trigger coverage without a cluster."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

import yaml


VPA_API = "autoscaling.k8s.io/v1/VerticalPodAutoscaler"
IMAGE_API = "image.openshift.io/v1/ImageStream"
TRIGGER = "image.openshift.io/triggers"
PATH = re.compile(r'^spec\.template\.spec\.(containers|initContainers)\[\?\(@\.name=="([^"]+)"\)\]\.image$')


def render(chart, values=(), settings=(), capabilities=(VPA_API, IMAGE_API), release="resource-smoke"):
    command = ["helm", "template", release, str(chart)]
    for capability in capabilities:
        command.extend(["--api-versions", capability])
    for value in values:
        command.extend(["--values", str(value)])
    for setting in settings:
        command.extend(["--set", setting])
    result = subprocess.run(command, capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return [item for item in yaml.safe_load_all(result.stdout) if item]


def check(resources, *, ha=False, deployment=False, metrics=False, scheduled=True, images=True, vpas=True):
    workloads = [item for item in resources if item["kind"] in ("Deployment", "StatefulSet")]
    autoscalers = [item for item in resources if item["kind"] == "VerticalPodAutoscaler"]
    streams = [item for item in resources if item["kind"] == "ImageStream"]
    assert len(workloads) == 3, "Expected API, gateway and Redis controllers"
    assert len(autoscalers) == (3 if vpas else 0), "Missing or duplicate VPAs"
    assert len(streams) == ((4 if metrics else 3) if images else 0), "Wrong ImageStream coverage"
    assert len({item["metadata"]["name"] for item in streams}) == len(streams), "ImageStream names collide"
    targets = {(item["spec"]["targetRef"]["kind"], item["spec"]["targetRef"]["name"]): item
               for item in autoscalers}
    tracked = {}
    for stream in streams:
        assert len(stream["metadata"]["name"]) <= 63
        assert stream["spec"]["lookupPolicy"]["local"] is True
        tags = stream["spec"]["tags"]
        assert len(tags) == 1 and tags[0]["name"] == "latest"
        tag = tags[0]
        assert tag["from"]["kind"] == "DockerImage"
        assert tag["importPolicy"]["scheduled"] is scheduled
        assert tag["importPolicy"]["importMode"] == "PreserveOriginal"
        assert tag["referencePolicy"]["type"] == "Source"
        tracked[stream["metadata"]["name"] + ":latest"] = tag["from"]["name"]
    used_streams = set()
    for workload in workloads:
        name = workload["metadata"]["name"]
        pod = workload["spec"]["template"]["spec"]
        containers = {(kind, container["name"]): container for kind in ("containers", "initContainers")
                      for container in pod.get(kind, [])}
        redis = any(container[1] == "redis-server" for container in containers)
        if redis:
            expected_kind = "Deployment" if deployment and not ha else "StatefulSet"
            assert workload["kind"] == expected_kind, "Wrong Redis controller kind"
            assert (("containers", "redis-sentinel") in containers) == ha
            assert (("containers", "redis-exporter") in containers) == metrics
        if vpas:
            key = (workload["kind"], name)
            assert key in targets, "VPA does not target " + name
            autoscaler = targets[key]
            assert autoscaler["spec"]["updatePolicy"]["updateMode"] == "Off"
            assert autoscaler["spec"]["targetRef"]["apiVersion"] == "apps/v1"
            policies = autoscaler["spec"]["resourcePolicy"]["containerPolicies"]
            # Init requests remain explicit; no claim of init-container VPA tuning.
            for container in pod["containers"]:
                container_name = container["name"]
                assert any(policy["containerName"] in ("*", container_name) for policy in policies)
            assert all(policy["controlledValues"] == "RequestsOnly" for policy in policies)
        triggers = json.loads(workload["metadata"].get("annotations", {}).get(TRIGGER, "[]"))
        if not images:
            continue  # Unknown annotations are inert on Kubernetes without ImageStream controllers.
        resolved = set()
        for trigger in triggers:
            match = PATH.fullmatch(trigger["fieldPath"])
            assert match, "Unsupported image-trigger path"
            key = match.group(1), match.group(2)
            assert key in containers, "Trigger targets a nonexistent container"
            assert key not in resolved, "Duplicate image trigger"
            resolved.add(key)
            assert trigger.get("paused", False) is False
            assert trigger["from"]["kind"] == "ImageStreamTag"
            stream_tag = trigger["from"]["name"]
            assert stream_tag in tracked, "Trigger has no matching ImageStream"
            assert tracked[stream_tag] == containers[key]["image"], "Imported source differs from the container image"
            used_streams.add(stream_tag)
        assert resolved == set(containers), "Some workload/init/sidecar images lack triggers"
    assert used_streams == set(tracked), "Unused ImageStream or missing trigger"


def reject(chart, settings, capabilities, expected, values=()):
    try:
        render(chart, values, settings=settings, capabilities=capabilities)
    except RuntimeError as error:
        assert expected.lower() in str(error).lower(), "Wrong validation error: " + str(error)
    else:
        raise AssertionError("Invalid configuration was accepted")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("chart", type=Path)
    parser.add_argument("--values-dir", type=Path,
                        help="directory containing the chart's supplied values overlays")
    args = parser.parse_args()
    overlays = args.values_dir or args.chart
    production = overlays / "values-production.yaml"
    metrics = overlays / "values-metrics.yaml"
    production_metrics = overlays / "values-production-metrics.yaml"
    cases = [
        ("standalone StatefulSet", (), (), {}),
        ("standalone Deployment", (), ("redis.useDeploymentWhenNonHA=true",), {"deployment": True}),
        ("HA StatefulSet", (production,), (), {"ha": True}),
        ("standalone exporter", (metrics,), (), {"metrics": True}),
        ("HA exporter", (production, production_metrics), (), {"ha": True, "metrics": True}),
        ("numeric exporter tag", (metrics,), ("redis.metrics.exporter.image.tag=1",), {"metrics": True}),
        ("manual image imports", (), ("global.openshift.imageStreams.scheduled=false",), {"scheduled": False}),
    ]
    for label, values, settings, expectations in cases:
        check(render(args.chart, values, settings), **expectations)
        print(label + ": VPA and all container image triggers verified", flush=True)
    portable = ("global.openshift.imageStreams.enabled=false", "apiService.vpa.enabled=false",
                "gateway.vpa.enabled=false", "redis.vpa.enabled=false")
    check(render(args.chart, settings=portable, capabilities=()), images=False, vpas=False)
    print("portable Kubernetes opt-out verified", flush=True)
    reject(args.chart, (), (VPA_API,), "ImageStream API")
    reject(args.chart, (), (IMAGE_API,), "VerticalPodAutoscaler CRD")
    reject(args.chart, ("redis.haMode.enabled=true",), (VPA_API, IMAGE_API), "trigger")
    reject(args.chart, ("redis.metrics.enabled=true",), (VPA_API, IMAGE_API), "trigger")
    reject(args.chart, ("redis.updateStrategyType=OnDelete",), (VPA_API, IMAGE_API), "RollingUpdate")
    for extra in ("extraContainers", "extraInitContainers"):
        reject(args.chart, (f"redis.{extra}[0].name=custom", f"redis.{extra}[0].image=example.org/custom:latest"),
               (VPA_API, IMAGE_API), extra)
    defaults = yaml.safe_load((overlays / "values.yaml").read_text(encoding="utf-8"))
    original = defaults["redis"]["customAnnotations"][TRIGGER]
    invalid = []
    duplicate = json.loads(original)
    duplicate.append(duplicate[0].copy())
    invalid.append((json.dumps(duplicate), "duplicate"))
    paused = json.loads(original)
    paused[0]["paused"] = True
    invalid.append((json.dumps(paused), "paused"))
    wrong_namespace = json.loads(original)
    wrong_namespace[0]["from"]["namespace"] = "some-other-namespace"
    invalid.append((json.dumps(wrong_namespace), "namespace"))
    wrong_source = json.loads(original)
    wrong_source[0]["from"]["name"] = "wrong-image:latest"
    invalid.append((json.dumps(wrong_source), "ImageStreamTag"))
    invalid.append(("not valid JSON", "trigger"))
    with tempfile.TemporaryDirectory(prefix="ddb-chart-values-") as directory:
        fixture = Path(directory) / "invalid.yaml"
        for annotation, expected in invalid:
            fixture.write_text(yaml.safe_dump({"redis": {"customAnnotations": {TRIGGER: annotation}}}),
                               encoding="utf-8")
            reject(args.chart, (), (VPA_API, IMAGE_API), expected, (fixture,))
    print("missing APIs and inconsistent Redis mode/trigger values rejected", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print("Chart resource check failed: " + str(error), file=sys.stderr)
        sys.exit(1)
