"""Assertions for dotnet/smoke/run.sh. Usage: assert.py <workdir>"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "smoke"))
from smokelib import Checker  # noqa: E402

work = sys.argv[1]
c = Checker(work, "kloudmate-dotnet")

# 1. defaults
c.common("defaults", "default", "smoke-defaults", "systemd:smoke.service")
names = c.names("default", "metrics")
c.check(any(n.startswith(("process.runtime.dotnet.", "dotnet.")) for n in names), "[defaults] .NET runtime metrics present")
c.check("process.cpu.time" in names, "[defaults] process metrics present")
st = c.status("defaults")
c.check(st is not None, "[defaults] status file written")
if st:
    c.check(st["result"] == "ok", f"[defaults] status result={st['result']!r} reason={st['reason']!r}")
    c.check(st["km.service_id"] == "systemd:smoke.service", "[defaults] status km.service_id")
    ep = st["effective_config"].get("OTEL_EXPORTER_OTLP_ENDPOINT", {})
    c.check(ep == {"value": "http://127.0.0.1:4318", "origin": "distro_default"}, f"[defaults] endpoint effective config {ep}")
    c.check(st["overridden_by_app"] == [], f"[defaults] overridden_by_app {st['overridden_by_app']}")

# 2. the app's own env wins
c.common("appenv", "app", "smoke-app", "app-own")
c.check(not any(r.get("service.name") == "smoke-app" for r in c.resources("default", "traces")),
        "[appenv] nothing went to the default endpoint")
st = c.status("appenv")
if st:
    ep = st["effective_config"].get("OTEL_EXPORTER_OTLP_ENDPOINT", {})
    c.check(ep == {"value": "http://127.0.0.1:14318", "origin": "app_env"}, f"[appenv] endpoint effective config {ep}")
    c.check(st["overridden_by_app"] == ["OTEL_EXPORTER_OTLP_ENDPOINT"], f"[appenv] overridden_by_app {st['overridden_by_app']}")

# 3. foreign agent
st = c.status("foreign")
c.check(st is not None and (st["result"], st["reason"]) == ("degraded", "foreign_agent:ddtrace"),
        f"[foreign] status degraded/foreign_agent:ddtrace: {st and (st['result'], st['reason'])}")

# 5. config file, upstream file mode off
c.common("file-env", "file", "smoke-file-env", "systemd:smoke-file.service")
st = c.status("file-env")
c.check(st is not None, "[file-env] status file written to the block's status_dir")
if st:
    c.check(st["config_hash"].startswith("sha256:"), "[file-env] status config_hash")
    c.check(st["km.service_id"] == "systemd:smoke-file.service", "[file-env] km.service_id from the block")
    ep = st["effective_config"].get("OTEL_EXPORTER_OTLP_ENDPOINT", {})
    c.check(ep.get("value") == "http://127.0.0.1:24318", f"[file-env] endpoint from the block {ep}")
    c.check(st["overridden_by_app"] == ["OTEL_SERVICE_NAME"], f"[file-env] overridden_by_app {st['overridden_by_app']}")

# 6. config file in upstream's file mode, the plugin listed in the file
c.common("file-upstream", "upstream", "smoke-file", "systemd:smoke-file.service")
st = c.status("file-upstream")
c.check(st is not None and st["result"] == "ok", f"[file-upstream] the plugin loaded from plugins_list: {st and st['result']}")

# 4. plugin missing
c.check("app ran" in open(os.path.join(work, "app-noplugin.log")).read(), "[noplugin] the app ran without the plugin")
c.check(c.status("noplugin") is None, "[noplugin] no status file (the plugin never loaded)")

c.done()
