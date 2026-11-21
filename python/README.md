# KloudMate OpenTelemetry Python distribution

`kloudmate-opentelemetry-distro`: the upstream OpenTelemetry Python SDK and zero-code instrumentation, with
KloudMate defaults, service identity and a startup health check. Instrumentation is the upstream
`opentelemetry-instrumentation-*` packages, unmodified.

| | |
| --- | --- |
| Built on | `opentelemetry-sdk` 1.45.2, `opentelemetry-instrumentation` 0.66b2 |
| Runtime | CPython 3.10 – 3.14 |
| Platforms | Linux x86-64, arm64 (glibc) |
| Status | Preview (0.2.0) |

## How it loads

The distribution registers the standard entry points, so `opentelemetry-instrument` and the bootstrap both pick
it up:

```toml
[project.entry-points.opentelemetry_distro]
kloudmate = "kloudmate_otel.distro:KloudMateDistro"

[project.entry-points.opentelemetry_configurator]
kloudmate = "kloudmate_otel.configurator:KloudMateConfigurator"
```

**With the KloudMate Agent**, nothing changes in your app. The agent puts the bundle directory on `PYTHONPATH`:

```
/opt/kmagent/agents/python/
  sitecustomize.py      bootstrap: startup check, then auto-instrumentation
  3.10/ 3.11/ ...       one package directory per CPython minor (C-extension wheels are version-specific)
```

`sitecustomize.py` runs the startup check, adds the directory for the running interpreter, selects this
distribution (`OTEL_PYTHON_DISTRO=kloudmate`, `OTEL_PYTHON_CONFIGURATOR=kloudmate`, unless you set them), and starts
auto-instrumentation. It is written so every CPython from 2.7 can parse it: on a version it doesn't support, the app
runs untouched and the status file says why.

**Manually**:

```sh
pip install kloudmate-opentelemetry-distro opentelemetry-instrumentation-flask   # plus your libraries' instrumentations
OTEL_PYTHON_DISTRO=kloudmate OTEL_PYTHON_CONFIGURATOR=kloudmate OTEL_SERVICE_NAME=checkout \
  opentelemetry-instrument python app.py
```

## Defaults

Each default applies only when you haven't set the variable yourself.

| Variable | Upstream default | KloudMate default |
| --- | --- | --- |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` (opentelemetry-distro) | `http/protobuf` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | `http://127.0.0.1:4318`, or `distribution.kloudmate.agent.otlp_endpoint` |
| `OTEL_TRACES_EXPORTER` / `OTEL_METRICS_EXPORTER` / `OTEL_LOGS_EXPORTER` | `otlp` | `otlp` |
| `OTEL_PROPAGATORS` | `tracecontext,baggage` | `tracecontext,baggage` |
| Logs | handler only with the deprecated SDK switch | stdlib `logging` exported through `opentelemetry-instrumentation-logging` |
| Runtime metrics | none | `process.*` and `cpython.*` through `opentelemetry-instrumentation-system-metrics` |
| `OTEL_PYTHON_SYSTEM_METRICS_EXCLUDED_METRICS` | none | from `runtime_metrics.profile`: `standard` → `system.*,process.runtime.*`, `extended` → `system.*`, `off` → `*` |

Host-level `system.*` metrics are left to the KloudMate Agent, which collects them once per host.

## Config file

With `OTEL_CONFIG_FILE` set, the SDK's declarative configuration is used (file format 1.0). The distribution:

- reads `distribution.kloudmate`;
- fills each `otlp_http` exporter that has no `endpoint` with the agent endpoint;
- adds `km.service_id` (unless the file sets it) and `telemetry.distro.*` to the resource;
- writes the status file;
- hands the result to the upstream `configure_sdk`.

It never replaces a value the file sets. If the file can't be loaded, the status says `error` /
`config_parse_error` and the app runs without OpenTelemetry instead of failing to start.

## Status file

`<status_dir>/<pid>.json`, as described in the [repository README](../README.md#health-and-status). Reasons
specific to Python:

| `result` | `reason` | When |
| --- | --- | --- |
| `unsupported_runtime` | `python_too_old` | CPython older than 3.10 |
| `unsupported_runtime` | `no_bundle_for_python_X.Y` | no package directory for this minor |
| `unsupported_runtime` | `python_implementation:<name>` | PyPy and others |
| `disabled` | `sdk_disabled` | `OTEL_SDK_DISABLED=true` |
| `degraded` | `foreign_agent:<vendor>` | `ddtrace`, `newrelic`, `elasticapm` already loaded |
| `error` | `config_parse_error`, `initialize_failed:<type>`, `configurator_failed` | the distribution or the file failed; the app still runs |

## Limitations

- The upstream OTLP/HTTP exporter has no Unix-socket transport, so `agent.otlp_socket` is skipped and TCP is used.
- The status lists the instrumentations the distro activated as they load, so it is rewritten a few times at
  startup.
- Planned: live configuration over OpAMP, and optional continuous profiling.

## Development

```sh
pip install -e . pytest && pytest tests     # unit tests
smoke/run.sh                                 # end-to-end against a local OTLP sink (Go; Docker optional)
release.sh                                   # wheel, sitecustomize.py, km-manifest.json, SHA256SUMS
```

The smoke test builds a bundle shaped like the agent's and runs five scenarios:

1. KloudMate defaults;
2. your own `OTEL_*` settings win;
3. a config file;
4. a broken config file (the app still runs);
5. Python 2.7 (the bootstrap parses, and the status file says `python_too_old`).
