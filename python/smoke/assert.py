"""Assertions for python/smoke/run.sh. Usage: assert.py <workdir>"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "smoke"))
from smokelib import Checker  # noqa: E402

c = Checker(sys.argv[1], "kloudmate-python")
SIGNALS = ("traces", "metrics", "logs")

# 1. defaults
c.common("defaults", "default", "smoke-defaults", "systemd:smoke.service", SIGNALS)
names = c.names("default", "metrics")
c.check(any(n.startswith(("process.", "cpython.")) for n in names), f"[defaults] runtime metrics present: {sorted(names)[:6]}")
c.check(not any(n.startswith("system.") for n in names), "[defaults] no host-level system.* metrics")
c.check(not any(n.startswith("process.runtime.") for n in names), "[defaults] no deprecated process.runtime.* metrics")
st = c.status("defaults")
c.check(st is not None, "[defaults] status file written")
if st:
    c.check(st["result"] == "ok", f"[defaults] status result={st['result']!r} reason={st['reason']!r}")
    c.check(st["km.service_id"] == "systemd:smoke.service", "[defaults] status km.service_id")
    ep = st["effective_config"].get("OTEL_EXPORTER_OTLP_ENDPOINT", {})
    c.check(ep == {"value": "http://127.0.0.1:4318", "origin": "distro_default"}, f"[defaults] endpoint effective config {ep}")
    c.check(st["overridden_by_app"] == [], f"[defaults] overridden_by_app {st['overridden_by_app']}")
    c.check("urllib" in st["instrumentations"] and "system_metrics" in st["instrumentations"],
            f"[defaults] instrumentations listed: {st['instrumentations']}")
    pr = st["effective_config"].get("OTEL_EXPORTER_OTLP_PROTOCOL", {})
    c.check(pr == {"value": "http/protobuf", "origin": "distro_default"}, f"[defaults] protocol effective config {pr}")

# 2. the app's own env wins
c.common("appenv", "app", "smoke-app", "app-own", SIGNALS)
c.check(not any(r.get("service.name") == "smoke-app" for r in c.resources("default", "traces")),
        "[appenv] nothing went to the default endpoint")
st = c.status("appenv")
if st:
    ep = st["effective_config"].get("OTEL_EXPORTER_OTLP_ENDPOINT", {})
    c.check(ep == {"value": "http://127.0.0.1:14318", "origin": "app_env"}, f"[appenv] endpoint effective config {ep}")
    c.check(st["km.service_id"] == "app-own", "[appenv] status km.service_id is the app's")
    c.check(st["overridden_by_app"] == ["OTEL_EXPORTER_OTLP_ENDPOINT"], f"[appenv] overridden_by_app {st['overridden_by_app']}")

# 3. declarative config file
c.common("file", "file", "smoke-file", "systemd:smoke-file.service", SIGNALS)
st = c.status("file")
c.check(st is not None, "[file] status file written to distribution.kloudmate.status_dir")
if st:
    c.check(st["result"] == "ok", f"[file] status result={st['result']!r} reason={st['reason']!r}")
    c.check(st["config_hash"].startswith("sha256:"), "[file] status config_hash")
    c.check(st["km.service_id"] == "systemd:smoke-file.service", "[file] status km.service_id from the block")
    c.check(st["overridden_by_app"] == [], f"[file] overridden_by_app {st['overridden_by_app']}")
    c.check("urllib" in st["instrumentations"], f"[file] instrumentations listed: {st['instrumentations']}")
st = c.status("file-app")
if st:
    c.check(st["overridden_by_app"] == ["OTEL_SERVICE_NAME"], f"[file-app] overridden_by_app {st['overridden_by_app']}")
    sn = st["effective_config"].get("OTEL_SERVICE_NAME", {})
    c.check(sn == {"value": "smoke-file-app", "origin": "app_env"}, f"[file-app] service name effective config {sn}")

# 4. broken config file
st = c.status("broken")
c.check(st is not None and st["result"] == "error" and st["reason"] == "config_parse_error",
        f"[broken] status error/config_parse_error: {st and (st['result'], st['reason'])}")

# 5. Python 2.7 (when Docker was available)
if os.path.isdir(os.path.join(sys.argv[1], "status-py27")):
    st = c.status("py27")
    c.check(st is not None and (st["result"], st["reason"]) == ("unsupported_runtime", "python_too_old"),
            f"[py27] status unsupported_runtime/python_too_old: {st and (st['result'], st['reason'])}")
    c.check(st is not None and st["distro"]["version"] != "", "[py27] status carries the distro version")

c.done()
