"""Assertions for nodejs/smoke/run.sh. Usage: assert.py <workdir>"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "smoke"))
from smokelib import Checker  # noqa: E402

work = sys.argv[1]
c = Checker(work, "kloudmate-nodejs")

# 1. defaults
c.common("defaults", "default", "smoke-defaults", "systemd:smoke.service")
names = c.names("default", "metrics")
c.check(any(n.startswith(("nodejs.", "v8js.")) for n in names), f"[defaults] Node.js runtime metrics present: {sorted(names)[:5]}")
c.check(any(n.startswith("http.client") for n in names), "[defaults] HTTP client metrics present")
st = c.status("defaults")
c.check(st is not None, "[defaults] status file written")
if st:
    c.check(st["result"] == "ok", f"[defaults] status result={st['result']!r} reason={st['reason']!r}")
    c.check(st["km.service_id"] == "systemd:smoke.service", "[defaults] status km.service_id")
    ep = st["effective_config"].get("OTEL_EXPORTER_OTLP_ENDPOINT", {})
    c.check(ep == {"value": "http://127.0.0.1:4318", "origin": "distro_default"}, f"[defaults] endpoint effective config {ep}")
    c.check("http" in st["instrumentations"], "[defaults] status lists instrumentations")
    c.check("router" not in st["instrumentations"], "[defaults] router instrumentation off by default")

# 2. the app's own env wins
c.common("appenv", "app", "smoke-app", "app-own")
c.check(not any(r.get("service.name") == "smoke-app" for r in c.resources("default", "traces")),
        "[appenv] nothing went to the default endpoint")
st = c.status("appenv")
if st:
    ep = st["effective_config"].get("OTEL_EXPORTER_OTLP_ENDPOINT", {})
    c.check(ep == {"value": "http://127.0.0.1:14318", "origin": "app_env"}, f"[appenv] endpoint effective config {ep}")
    c.check(st["overridden_by_app"] == ["OTEL_EXPORTER_OTLP_ENDPOINT"], f"[appenv] overridden_by_app {st['overridden_by_app']}")

# 4b. a config file whose ${OTEL_SERVICE_NAME} the app supplies
st = c.status("file-app")
if st:
    c.check(st["overridden_by_app"] == ["OTEL_SERVICE_NAME"], f"[file-app] overridden_by_app {st['overridden_by_app']}")

# 3. ESM
c.common("esm", "esm", "smoke-esm", "systemd:smoke-esm.service")

# 4. config file
c.common("file", "file", "smoke-file", "systemd:smoke-file.service")
st = c.status("file")
c.check(st is not None and st["config_hash"].startswith("sha256:"), "[file] status with config_hash")
if st:
    c.check(st["km.service_id"] == "systemd:smoke-file.service", "[file] status km.service_id from the block")
    c.check(st["overridden_by_app"] == [], f"[file] overridden_by_app {st['overridden_by_app']}")
    tr = st["effective_config"].get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", {})
    c.check(tr == {"value": "http://127.0.0.1:24318/v1/traces", "origin": "distro_default"}, f"[file] traces endpoint filled {tr}")
    mt = st["effective_config"].get("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", {})
    c.check(mt == {"value": "http://127.0.0.1:24318/v1/metrics", "origin": "file"}, f"[file] metrics endpoint from the file {mt}")
c.check(not any(r.get("service.name") == "smoke-file" for r in c.resources("default", "metrics")),
        "[file] no metrics leaked to the default endpoint")

# 5. SIGTERM
res = open(os.path.join(work, "sigterm.result")).read().strip()
c.check(res in ("exit=143", "exit=0"), f"[sigterm] the app exited on SIGTERM ({res})")
c.check(any(r.get("service.name") == "smoke-sigterm" for r in c.resources("default", "traces")),
        "[sigterm] buffered spans flushed before exit")

# 6. Node 16
if os.path.isdir(os.path.join(work, "status-node16")):
    st = c.status("node16")
    c.check(st is not None and (st["result"], st["reason"]) == ("unsupported_runtime", "node_too_old"),
            f"[node16] status unsupported_runtime/node_too_old: {st and (st['result'], st['reason'])}")
    c.check("app ran" in open(os.path.join(work, "app-node16.log")).read(), "[node16] the app ran")

c.done()
