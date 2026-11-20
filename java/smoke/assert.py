"""Assertions for java/smoke/run.sh. Usage: assert.py <workdir>"""
import glob
import json
import os
import sys

work = sys.argv[1]
fails = []


def records(name):
    p = os.path.join(work, name + ".jsonl")
    if not os.path.exists(p):
        return []
    return [json.loads(line) for line in open(p) if line.strip()]


def check(cond, msg):
    print(("  ok    " if cond else "  FAIL  ") + msg)
    if not cond:
        fails.append(msg)


def resources(recs, signal):
    return [r for rec in recs if rec["signal"] == signal for r in rec["resources"]]


def status(name):
    files = glob.glob(os.path.join(work, "status-" + name, "*.json"))
    return json.load(open(files[0])) if files else None


def common(name, sink, service_name, service_id):
    recs = records(sink)
    spans = resources(recs, "traces")
    metrics = resources(recs, "metrics")
    check(bool(spans), f"[{name}] spans reached the {sink} sink")
    check(bool(metrics), f"[{name}] metrics reached the {sink} sink")
    for r in spans[:1] + metrics[:1]:
        check(r.get("km.service_id") == service_id, f"[{name}] km.service_id={r.get('km.service_id')!r} want {service_id!r}")
        check(r.get("service.name") == service_name, f"[{name}] service.name={r.get('service.name')!r} want {service_name!r}")
        check(r.get("telemetry.distro.name") == "kloudmate-java", f"[{name}] telemetry.distro.name={r.get('telemetry.distro.name')!r}")
        check(bool(r.get("telemetry.distro.version")), f"[{name}] telemetry.distro.version set")
    names = {n for rec in recs if rec["signal"] == "metrics" for n in rec.get("names") or []}
    return names


# 1. defaults
names = common("defaults", "default", "smoke-defaults", "systemd:smoke.service")
check("jvm.memory.used" in names, "[defaults] stable JVM runtime metrics present")
check("jvm.buffer.memory.used" in names, "[defaults] experimental JVM runtime metrics on by default")
st = status("defaults")
check(st is not None, "[defaults] status file written")
if st:
    check(st["result"] == "ok", f"[defaults] status result={st['result']!r}")
    check(st["distro"]["name"] == "kloudmate-java", "[defaults] status distro.name")
    check(st["km.service_id"] == "systemd:smoke.service", "[defaults] status km.service_id")
    ep = st["effective_config"].get("OTEL_EXPORTER_OTLP_ENDPOINT", {})
    check(ep == {"value": "http://127.0.0.1:4318", "origin": "distro_default"}, f"[defaults] endpoint effective config {ep}")

# 2. the app's own env wins
common("appenv", "app", "smoke-app", "app-own")
check(not any(r.get("service.name") == "smoke-app" for r in resources(records("default"), "traces")),
      "[appenv] nothing went to the default endpoint")
st = status("appenv")
if st:
    ep = st["effective_config"].get("OTEL_EXPORTER_OTLP_ENDPOINT", {})
    check(ep == {"value": "http://127.0.0.1:14318", "origin": "app_env"}, f"[appenv] endpoint effective config {ep}")

# 3. declarative config file
names = common("file", "file", "smoke-file", "systemd:smoke-file.service")
check("jvm.buffer.memory.used" in names, "[file] experimental JVM runtime metrics on from runtime_metrics.profile")
st = status("file")
check(st is not None, "[file] status file written to distribution.kloudmate.status_dir")
if st:
    check(st["config_hash"].startswith("sha256:"), "[file] status config_hash")
    check(st["km.service_id"] == "systemd:smoke-file.service", "[file] status km.service_id from the block")

if fails:
    print(f"{len(fails)} assertion(s) failed")
    sys.exit(1)
