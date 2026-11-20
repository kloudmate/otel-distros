# KloudMate OpenTelemetry .NET distribution (in development)

A plugin for the upstream OpenTelemetry .NET automatic instrumentation. The upstream CLR profiler, startup hook
and instrumentation stay exactly as they are; the plugin is loaded with:

```sh
OTEL_DOTNET_AUTO_PLUGINS="KloudMate.OpenTelemetry.Plugin, KloudMate.OpenTelemetry"
```

| | |
| --- | --- |
| Built on | `opentelemetry-dotnet-instrumentation` 1.15.0 |
| Runtime | .NET 8 and later; .NET Framework 4.6.2 and later (Windows) |
| Platforms | Linux x86-64, arm64 (glibc); Windows x64 |
| Status | In development |

## What it adds over upstream

| Area | Upstream | KloudMate distribution |
| --- | --- | --- |
| Export | OTLP; protocol and endpoint from env | `http/protobuf` to `127.0.0.1:4318` unless you set your own |
| Resource | from env and detectors | adds `km.service_id` and `telemetry.distro.*` |
| Runtime metrics | on | on, with process metrics |
| Config file | partial (`OTEL_EXPERIMENTAL_FILE_BASED_CONFIGURATION_ENABLED`) | reads `distribution.kloudmate` where upstream doesn't |
| Health | none | startup status file ([specification](../contract/README.md#5-status-file)) |
| Remote configuration | none | planned |
