# KloudMate OpenTelemetry Node.js distribution

`@kloudmate/opentelemetry-node`: upstream OpenTelemetry JS and `@opentelemetry/auto-instrumentations-node`, with
KloudMate defaults, service identity and a startup health check. Instrumentation is upstream, unmodified.

| | |
| --- | --- |
| Built on | `@opentelemetry/auto-instrumentations-node` 0.77.0, `@opentelemetry/sdk-node` 0.219.0 |
| Runtime | Node.js 18.19+ or 20.6+ (the upstream SDK's range) |
| Module systems | CommonJS (`--require`) and ES modules (`--import`) |
| Platforms | Linux x86-64, arm64 (pure JavaScript, no native addons) |
| Status | Preview (0.1.0) |

## How it loads

**With the KloudMate Agent**, nothing changes in your app: the agent sets `NODE_OPTIONS`.

**Manually:**

```sh
npm install @kloudmate/opentelemetry-node
# CommonJS
NODE_OPTIONS="--require @kloudmate/opentelemetry-node/register" OTEL_SERVICE_NAME=checkout node app.js
# ES modules (also hooks import)
NODE_OPTIONS="--import @kloudmate/opentelemetry-node/register.mjs" OTEL_SERVICE_NAME=checkout node app.mjs
```

It replaces `@opentelemetry/auto-instrumentations-node/register`; don't load both.

## Defaults

Each default applies only when you haven't set the variable yourself.

| Variable | Upstream default | KloudMate default |
| --- | --- | --- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | `http://127.0.0.1:4318` |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` | `http/protobuf` |
| `OTEL_TRACES_EXPORTER` / `OTEL_METRICS_EXPORTER` / `OTEL_LOGS_EXPORTER` | `otlp` | `otlp` |
| `OTEL_PROPAGATORS` | `tracecontext,baggage` | `tracecontext,baggage` |
| `OTEL_NODE_DISABLED_INSTRUMENTATIONS` | `fs`, `host-metrics` off | also `router`, when you set neither instrumentation list. Express 5 runs on `router`, and the express instrumentation already traces it, so router spans would double every middleware |
| Runtime metrics | `runtime-node` (event loop, V8 heap, GC) | on; `runtime_metrics.profile: off` disables it |

Other behaviour, compared with upstream `register`:

- **Startup never fails the app.** Every step is guarded: the app always starts, and a failure is reported in the
  status file.
- **SIGTERM still stops the app.** Upstream adds a plain SIGTERM listener, which keeps an app that has no handler
  of its own from exiting. Here pending telemetry is flushed, and when no other handler exists the signal is
  re-raised so the default exit happens.
- **Your own metrics SDK wins.** The global `MeterProvider` is released right after start and reclaimed on the next
  tick, unless the app installed its own while loading. Instrumentation metrics keep flowing either way.

## Config file (opt-in)

Upstream reads `OTEL_CONFIG_FILE` only through `startNodeSDK`, which is still experimental: it doesn't apply
`sampler` settings yet, and in 0.219.0 it drops the metric exporter's `endpoint`. The KloudMate Agent therefore
keeps Node.js on environment variables (`reads_config_file: false` in the manifest). If you set `OTEL_CONFIG_FILE`
yourself, the distribution:

- reads `distribution.kloudmate` and adds `km.service_id` (unless the file sets it) and `telemetry.distro.*`;
- routes each signal through the endpoint the file gives it, or the agent endpoint when it gives none. This uses
  `OTEL_EXPORTER_OTLP_{TRACES,METRICS,LOGS}_ENDPOINT`, and your own value of those wins;
- hands the meter provider to the instrumentations, which upstream's experimental path doesn't do.

## Status file

`<status_dir>/<pid>.json`, as described in the [specification](../contract/README.md#5-status-file). It also lists
the enabled instrumentations. Reasons specific to Node.js:

| `result` | `reason` | When |
| --- | --- | --- |
| `unsupported_runtime` | `node_too_old` | Node.js below 18.19, or 19.x / 20.0–20.5 |
| `disabled` | `sdk_disabled` | `OTEL_SDK_DISABLED=true`, or `disabled: true` in the file |
| `degraded` | `foreign_agent:<vendor>` | `dd-trace`, `newrelic` or `elastic-apm-node` in `NODE_OPTIONS` or the command line |
| `error` | `config_parse_error`, `start_failed:<type>` | the file or the SDK failed; the app still runs |

`register.js` is written in syntax every Node.js release parses, so unsupported runtimes still get a status
file.

## Limitations

- The upstream OTLP exporters take no Unix-socket path from configuration, so `agent.otlp_socket` is skipped and
  TCP is used.
- Planned: remote configuration over OpAMP, and optional continuous profiling.

## Development

```sh
npm ci && npm test       # unit tests
smoke/run.sh             # end-to-end against a local OTLP sink (Go; Docker optional)
release.sh               # npm tarball, package-lock.json, km-manifest.json, SHA256SUMS
```

The smoke test runs six scenarios:

1. CommonJS defaults;
2. your own `OTEL_*` settings win;
3. ES modules;
4. a config file;
5. SIGTERM still exits and flushes;
6. Node.js 16 (the app runs, and the status file says `node_too_old`).
