# KloudMate OpenTelemetry .NET distribution

A plugin for the upstream OpenTelemetry .NET automatic instrumentation. The upstream CLR profiler, startup hook
and instrumentation are used exactly as released; the plugin adds KloudMate defaults, service identity and a
startup health check.

| | |
| --- | --- |
| Built on | `opentelemetry-dotnet-instrumentation` 1.15.0 (OpenTelemetry .NET 1.15.3) |
| Runtime | .NET 8 and later |
| Platforms | Linux x86-64, arm64 (glibc) |
| .NET Framework | the plugin targets `netstandard2.0` and is built for .NET Framework 4.6.2+ on Windows, but isn't tested there yet |
| Status | Preview (0.1.0) |

A plugin must reference exactly the OpenTelemetry versions upstream bundles, so each release works with one
upstream version only. That version is recorded in its `km-manifest.json`.

## How it loads

**With the KloudMate Agent**, nothing changes in your app: the agent installs the upstream instrumentation with the
plugin and sets the environment.

**Manually**, put `KloudMate.OpenTelemetry.dll` in the `net/` directory of your `OTEL_DOTNET_AUTO_HOME`, then
activate upstream as usual and add the plugin:

```sh
cp KloudMate.OpenTelemetry.dll "$OTEL_DOTNET_AUTO_HOME/net/"
. "$OTEL_DOTNET_AUTO_HOME/instrument.sh"
export OTEL_DOTNET_AUTO_PLUGINS="KloudMate.OpenTelemetry.Plugin, KloudMate.OpenTelemetry"
OTEL_SERVICE_NAME=checkout dotnet app.dll
```

If the assembly is missing, upstream runs with its own defaults and the app is unaffected.

## Defaults

Each default applies only when you haven't set the variable yourself.

| Setting | Upstream default | KloudMate default |
| --- | --- | --- |
| OTLP endpoint | `http://localhost:4318` | `http://127.0.0.1:4318` (per signal: `/v1/traces`, `/v1/metrics`, `/v1/logs`) |
| OTLP protocol | `http/protobuf` | `http/protobuf` |
| Propagators | `tracecontext,baggage` | `tracecontext,baggage` |
| Runtime and process metrics | on | on |
| `telemetry.distro.*` | `opentelemetry-dotnet-instrumentation` | `kloudmate-dotnet` and the plugin version |
| `km.service_id` | – | from `KM_SERVICE_ID`, when `OTEL_RESOURCE_ATTRIBUTES` has none |

An endpoint you set with `OTEL_EXPORTER_OTLP_ENDPOINT` or `OTEL_EXPORTER_OTLP_{TRACES,METRICS,LOGS}_ENDPOINT`, or a
protocol you set, is left alone.

## Status file

`<status_dir>/<pid>.json`, as described in the [specification](../contract/README.md#5-status-file). Reasons
specific to .NET:

| `result` | `reason` | When |
| --- | --- | --- |
| `disabled` | `sdk_disabled` | `OTEL_SDK_DISABLED=true` |
| `degraded` | `foreign_agent:<vendor>` | Datadog (`DD_TRACE_ENABLED`, `DD_DOTNET_TRACER_HOME`), New Relic or Elastic APM settings are present |

## Limitations

- **No config file yet.** The `distribution.kloudmate` block isn't read in this release, because upstream's
  file-based configuration for .NET is experimental and partial. Settings come from the environment
  (`KM_SERVICE_ID`, `KM_SDK_STATUS_DIR`), and the manifest declares `reads_config_file: false`.
- **No Unix sockets.** The .NET OTLP exporter has no Unix-socket transport, so TCP is used.
- **Planned:** remote configuration over OpAMP.

## Development

No local .NET SDK is needed: `dotnet.sh` runs the SDK in Docker and passes an HTTPS proxy and its CA bundle through
when one is configured.

```sh
./dotnet.sh build -c Release -o build/out    # the plugin
smoke/run.sh                                  # end-to-end under upstream 1.15.0 against a local OTLP sink
release.sh                                    # reproducible DLL, km-manifest.json, SHA256SUMS
```

The smoke test runs four scenarios:

1. KloudMate defaults;
2. your own `OTEL_*` settings win;
3. another vendor's agent is reported as `degraded`;
4. without the plugin, the app still runs on upstream defaults.
