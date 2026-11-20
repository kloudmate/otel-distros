# KloudMate .NET distro

A **plugin assembly** for the upstream automatic instrumentation, loaded through
`OTEL_DOTNET_AUTO_PLUGINS="KloudMate.OpenTelemetry.Plugin, KloudMate.OpenTelemetry"`. The upstream
`instrument.sh` and CLR profiler stay as they are.

**Upstream base:** `opentelemetry-dotnet-instrumentation` **1.15.0** (`DOTNET_VERSION` in
`scripts/otel-agent-bundles/build.sh`; upstream is at 1.19.1, bump tracked as plan 0.4).

## What it adds over upstream

| Area | Upstream 1.15.0 | KloudMate distro |
| --- | --- | --- |
| Resource | env | `km.service_id`, `telemetry.distro.*` (`ConfigureTracesOptions` / resource hook) |
| Runtime metrics | on | on; process metrics on |
| Config file | `OTEL_EXPERIMENTAL_FILE_BASED_CONFIGURATION_ENABLED` (partial) | reads `distribution.kloudmate` where upstream doesn't |
| Live config | built-in OpAMP client (alpha) | configured to the local server, Phase 3 |
| Profiling | none bundled | eBPF only (Pyroscope's CLR profiler conflicts with OTel's; plan Phase 4) |

## Layout (planned)

```
dotnet/
  KloudMate.OpenTelemetry.csproj   netstandard2.0 + net8.0
  src/Plugin.cs
  km-manifest.json
```
