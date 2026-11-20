"""Assertions for java/smoke/run.sh. Usage: assert.py <workdir>"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "smoke"))
from smokelib import Checker  # noqa: E402

c = Checker(sys.argv[1], "kloudmate-java")

# 1. defaults
c.common("defaults", "default", "smoke-defaults", "systemd:smoke.service")
names = c.names("default", "metrics")
c.check("jvm.memory.used" in names, "[defaults] stable JVM runtime metrics present")
c.check("jvm.buffer.memory.used" in names, "[defaults] experimental JVM runtime metrics on by default")
st = c.status("defaults")
c.check(st is not None, "[defaults] status file written")
if st:
    c.check(st["result"] == "ok", f"[defaults] status result={st['result']!r}")
    c.check(st["distro"]["name"] == "kloudmate-java", "[defaults] status distro.name")
    c.check(st["km.service_id"] == "systemd:smoke.service", "[defaults] status km.service_id")
    ep = st["effective_config"].get("OTEL_EXPORTER_OTLP_ENDPOINT", {})
    c.check(ep == {"value": "http://127.0.0.1:4318", "origin": "distro_default"}, f"[defaults] endpoint effective config {ep}")

# 2. the app's own env wins
c.common("appenv", "app", "smoke-app", "app-own")
c.check(not any(r.get("service.name") == "smoke-app" for r in c.resources("default", "traces")),
        "[appenv] nothing went to the default endpoint")
st = c.status("appenv")
if st:
    ep = st["effective_config"].get("OTEL_EXPORTER_OTLP_ENDPOINT", {})
    c.check(ep == {"value": "http://127.0.0.1:14318", "origin": "app_env"}, f"[appenv] endpoint effective config {ep}")

# 3. declarative config file
c.common("file", "file", "smoke-file", "systemd:smoke-file.service")
c.check("jvm.buffer.memory.used" in c.names("file", "metrics"), "[file] experimental JVM runtime metrics on from runtime_metrics.profile")
st = c.status("file")
c.check(st is not None, "[file] status file written to distribution.kloudmate.status_dir")
if st:
    c.check(st["config_hash"].startswith("sha256:"), "[file] status config_hash")
    c.check(st["km.service_id"] == "systemd:smoke-file.service", "[file] status km.service_id from the block")

c.done()
