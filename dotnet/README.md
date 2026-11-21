# KloudMate OpenTelemetry .NET distribution

A plugin for the upstream OpenTelemetry .NET automatic instrumentation. The upstream CLR profiler, startup hook
and instrumentation are used exactly as released; the plugin adds KloudMate defaults, service identity and a
startup health check.

| | |
| --- | --- |
| Built on | `opentelemetry-dotnet-instrumentation` 1.19.1 (OpenTelemetry .NET 1.20.1, plugin API 1.19.1) |
| Runtime | .NET 8 and later |
| Platforms | Linux x86-64, arm64 (glibc) |
| .NET Framework | not in this release: the plugin targets `net8.0` |
| Status | Preview (0.2.0) |

A plugin must reference exactly the plugin API and OpenTelemetry versions upstream bundles, so each release works
with one upstream version only. That version is recorded in its `km-manifest.json`. From 1.19 upstream loads plugins
through the `IPlugin` interface only; this release implements it.

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
| OTLP endpoint | `http://localhost:4318` | `http://127.0.0.1:4318` (per signal: `/v1/traces`, `/v1/metrics`, `/v1/logs`); the agent's socket when it is used ([rules](../contract/README.md#the-agents-socket)) |
| OTLP protocol | `http/protobuf` | `http/protobuf`; `grpc` over the agent's socket |
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

## Config file

When `OTEL_CONFIG_FILE` points at a file, the plugin reads its `distribution.kloudmate` block itself (service id,
agent endpoint, runtime metrics profile, status directory; see the [specification](../contract/README.md#1-configuration-file)),
whether or not upstream's own file-based configuration is on. The status then carries the file's hash, and
`${OTEL_*}` substitutions you supply are listed in `overridden_by_app`.

Upstream's file-based configuration is experimental and off by default. To use it
(`OTEL_EXPERIMENTAL_FILE_BASED_CONFIGURATION_ENABLED=true`), list the plugin in the file, because upstream then ignores
`OTEL_DOTNET_AUTO_PLUGINS`, and list the instrumentations you want: in that mode none are on by default.

```yaml
plugins/development:
  plugins_list: "KloudMate.OpenTelemetry.Plugin, KloudMate.OpenTelemetry"   # a string, not a YAML list
instrumentation/development:
  dotnet:
    traces:
      aspnetcore:
      httpclient:
```

An endpoint the file sets on an exporter wins over the agent endpoint. The KloudMate Agent keeps .NET services on
environment variables (`reads_config_file: false` in the manifest) until upstream's file mode is stable.

## Limitations

- **Planned:** remote configuration over OpAMP.
- Upstream 1.20 supports .NET 10 and later only. This release stays on 1.19.1, the last one that runs .NET 8 and 9.

## Development

No local .NET SDK is needed: `dotnet.sh` runs the SDK in Docker and passes an HTTPS proxy and its CA bundle through
when one is configured.

```sh
./dotnet.sh build -c Release -o build/out    # the plugin
smoke/run.sh                                  # end-to-end under upstream 1.19.1 against a local OTLP sink
release.sh                                    # reproducible DLL, km-manifest.json, SHA256SUMS
```

The smoke test runs these scenarios:

1. KloudMate defaults;
2. your own `OTEL_*` settings win;
3. another vendor's agent is reported as `degraded`;
4. without the plugin, the app still runs on upstream defaults;
5. a config file with upstream's file mode off: the plugin reads `distribution.kloudmate`;
6. upstream's file mode, with the plugin listed in the file;
7. the agent's socket: env mode, a socket path with nothing listening, and upstream's file mode with `otlp_grpc`
   exporters.
