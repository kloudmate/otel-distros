# KloudMate Python distro

A distro package, `kloudmate-opentelemetry-distro`, registered through the standard entry points that
`opentelemetry-instrument` and `sitecustomize` already load:

```toml
[project.entry-points.opentelemetry_distro]
kloudmate = "kloudmate_otel.distro:KloudMateDistro"

[project.entry-points.opentelemetry_configurator]
kloudmate = "kloudmate_otel.configurator:KloudMateConfigurator"
```

**Upstream base:** `opentelemetry-distro` **0.64b0**, SDK/API **1.43.0** (the pins in
`scripts/otel-agent-bundles/python/requirements.txt`). Bundled per CPython minor (3.10–3.14) as today.

## What it adds over upstream

| Area | Upstream 0.64b0 | KloudMate distro |
| --- | --- | --- |
| Runtime metrics | none unless `opentelemetry-instrumentation-system-metrics` is installed | bundles it with `OTEL_PYTHON_SYSTEM_METRICS_EXCLUDED_METRICS=system.*` (process/runtime only; plan 0.3) |
| Defaults | exporter `otlp` (gRPC) | `http/protobuf`, `tracecontext,baggage`, logs on |
| Resource | env | `km.service_id`, `telemetry.distro.*` |
| Config file | `OTEL_CONFIG_FILE` (file format 1.1 in this SDK line) | reads it plus `distribution.kloudmate` |
| Startup check | silent no-op if a dependency is missing | the bootstrap in `sitecustomize.py` (Python 2-parseable) reports `result`/`reason` to `/run/kmagent/sdk-status/<pid>.json` |
| Live config | none | `opentelemetry-opamp-client` (0.6b0) to the local OpAMP server, Phase 3 |
| Profiling | none | optional `pyroscope-io`, off by default |

## Layout (planned)

```
python/
  pyproject.toml
  src/kloudmate_otel/{__init__,distro,configurator,resource,discovery,status}.py
  km-manifest.json
```
