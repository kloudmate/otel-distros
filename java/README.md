# KloudMate Java distro

An **extension jar** loaded by the stock upstream Java agent. We don't repackage or shade the agent.

```
-javaagent:/opt/kmagent/agents/java/stable/opentelemetry-javaagent.jar
-Dotel.javaagent.extensions=/opt/kmagent/agents/java/stable/kloudmate-otel-extension.jar
```

**Upstream base:** `opentelemetry-javaagent` **2.29.0** (`JAVA_AGENT_VERSION` in `scripts/otel-agent-bundles/build.sh`).
The extension is built against the matching `opentelemetry-javaagent-extension-api` and is released for one
agent minor at a time; `km-manifest.json` carries the agent version it was tested with.

## What it adds over the upstream agent

| Area | Upstream 2.29.0 default | KloudMate distro |
| --- | --- | --- |
| Runtime metrics | Stable JVM metrics only; experimental (buffer pools, GC cause, etc.) off | `otel.instrumentation.runtime-telemetry.emit-experimental-metrics=true` by default (plan 0.3) |
| Resource | `service.name` from env | adds `km.service_id`, `telemetry.distro.name=kloudmate-java`, `telemetry.distro.version` (`ResourceProvider`) |
| Config | env / system properties; `otel.experimental.config.file` | reads the generated declarative config and `distribution.kloudmate` (`DeclarativeConfigurationCustomizerProvider`) |
| Endpoint | `http://localhost:4318` | local-agent discovery: UDS → `127.0.0.1:4318` → env (`AutoConfigurationCustomizerProvider`) |
| Attached JVMs | runtime attach gets no config | the extension reads `KM_SERVICE_ID` / the config file path from agent args, so jattach-loaded JVMs get config |
| Live config | none | OpAMP client (contrib `opamp-client` + `dynamic-control`) to `127.0.0.1:4320`, Phase 3 |
| Profiling | none | optional `pyroscope-otel` wiring, off by default (plan Phase 4 P3) |

## Status: 0.1.0

Done and tested (`smoke/run.sh`, `gradle test`):

- **Env mode** (no config file, `KmAutoConfigCustomizer`): KloudMate defaults as the lowest-priority property
  source (`otel.exporter.otlp.endpoint=http://127.0.0.1:4318`, `http/protobuf`, `tracecontext,baggage`,
  experimental JVM metrics on). Anything the app sets as env or `-D` wins.
- **Declarative mode** (`OTEL_CONFIG_FILE`, which agent 2.29.0 reads natively; `KmDeclarativeConfigCustomizer`):
  reads `distribution.kloudmate`, fills only what the file leaves unset (OTLP endpoints from `agent.otlp_endpoint`,
  `runtime_telemetry.emit_experimental_metrics/development`, or `enabled: false` for profile `off`). Unknown keys are ignored.
- **Resource:** `km.service_id` (block → `KM_SERVICE_ID` → `-Dkm.service.id`) added only when the resource has none,
  so an app-set value is never rewritten; `telemetry.distro.name=kloudmate-java`, `telemetry.distro.version`.
- **Startup check + status:** `<status_dir>/<pid>.json` (contract §5) with `result`/`reason`, `config_hash`, and effective
  config with origins (`distro_default`, `file`, `app_env`). Reasons today: `sdk_disabled`, `foreign_agent:<vendor>`
  (from `/proc/self/cmdline`; JMX is never touched in premain). Written only if the directory exists;
  `KM_SDK_STATUS_DIR` overrides the default `/run/kmagent/sdk-status`.
- Never throws: every step is guarded; a failure logs one `[kloudmate-java]` line on stderr and the agent continues
  with upstream behaviour. No `java.util.logging` (it breaks apps that install their own LogManager).
- **Release:** `release.sh` builds the jar (reproducible), `km-manifest.json` with real sha256s and provenance,
  and `SHA256SUMS`.

Smoke scenarios: (1) defaults: export reaches `127.0.0.1:4318` with KM attributes and `jvm.buffer.memory.used`;
(2) the app's own `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_RESOURCE_ATTRIBUTES` / `OTEL_SERVICE_NAME` win; (3) a
generated-style config file with exporters without endpoints, filled from the block.

Not yet:

- **UDS:** the Java OTLP exporters have no Unix-socket transport, so `agent.otlp_socket` is skipped (TCP fallback).
- **Attached JVMs (jattach):** no env reaches them; `-Dkm.service.id` works if km-agent passes it, but a config-file
  handoff for attached JVMs needs a contract decision.
- `overridden_by_app` stays empty: with `${VAR:-default}` substitutions the file can't tell an app value from KM's.
- OpAMP (Phase 3), `pyroscope-otel` (Phase 4).

## Layout

```
java/
  build.gradle.kts / settings.gradle.kts   Gradle, Java 8 bytecode, OTel deps compileOnly (agent provides them)
  src/main/java/com/kloudmate/otel/
    KmAutoConfigCustomizer.java          env mode: defaults, resource, status
    KmDeclarativeConfigCustomizer.java   file mode: distribution.kloudmate, fills, status
    KmSettings.java                      the block / env, endpoint discovery
    KmStartupCheck.java  KmStatus.java   startup check, status file
  km-manifest.json   release.sh   smoke/
```
