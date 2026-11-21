# KloudMate OpenTelemetry Java distribution

An extension for the upstream OpenTelemetry Java agent. The agent and its instrumentation are used exactly as
released: nothing is repackaged, shaded or patched. The extension adds KloudMate defaults, service identity and a
startup health check.

| | |
| --- | --- |
| Built on | `opentelemetry-javaagent` 2.34.0 (SDK 1.68.0) |
| Runtime | Java 8 and later, any JVM vendor |
| Platforms | Linux x86-64, arm64 |
| Status | Preview (0.2.0) |

Each release is tested against one agent version, recorded in its `km-manifest.json`. Use it with that version.

## How it loads

**With the KloudMate Agent**, nothing changes in your app: the agent adds both jars to `JAVA_TOOL_OPTIONS`.

**Manually:**

```sh
java -javaagent:/opt/otel/opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=/opt/otel/kloudmate-otel-extension.jar \
     -Dotel.service.name=checkout \
     -jar app.jar
```

## Defaults

Each default applies only when you haven't set the property (`-Dotel...`) or variable (`OTEL_...`) yourself.

| Setting | Upstream default | KloudMate default |
| --- | --- | --- |
| `otel.exporter.otlp.endpoint` | `http://localhost:4318` | `http://127.0.0.1:4318`, or `distribution.kloudmate.agent.otlp_endpoint` |
| `otel.exporter.otlp.protocol` | `http/protobuf` | `http/protobuf` |
| `otel.propagators` | `tracecontext,baggage` | `tracecontext,baggage` |
| JVM runtime metrics | stable metrics only | stable and experimental (buffer pools, GC cause and more), from `runtime_metrics.profile` |
| `telemetry.distro.*` | `opentelemetry-java-instrumentation` | `kloudmate-java` and the extension version |
| `km.service_id` | – | from `KM_SERVICE_ID` or `-Dkm.service.id`, when the resource has none |

`127.0.0.1` rather than `localhost` matters on hosts where `localhost` resolves to `::1` first and the local agent
listens on IPv4 only.

## Config file

The agent reads `OTEL_CONFIG_FILE` (or `-Dotel.config.file`) natively. The extension then:

- reads `distribution.kloudmate`;
- fills each `otlp_http` exporter that has no `endpoint` with the agent endpoint (without it, the agent would
  send to `localhost:4318`);
- adds `km.service_id` (unless the file sets it) and `telemetry.distro.*` to the resource;
- sets the runtime-metrics options under `instrumentation/development.java.runtime_telemetry`, unless the file
  sets them.

Runtime metric profiles: `standard` and `extended` turn on the experimental JVM metrics; `off` disables the
runtime-telemetry instrumentation.

## Status file

`<status_dir>/<pid>.json`, as described in the [specification](../contract/README.md#5-status-file). Reasons
specific to Java:

| `result` | `reason` | When |
| --- | --- | --- |
| `disabled` | `sdk_disabled` | `otel.sdk.disabled=true`, or `disabled: true` in the file |
| `degraded` | `foreign_agent:<vendor>` | another vendor's `-javaagent` on the command line (Datadog, New Relic, Elastic, AppDynamics, Dynatrace, SkyWalking, Pinpoint) |
| `denied` | `bytecode_verification_off` | the JVM runs without bytecode verification (`-Xverify:none`, `-noverify` or `-XX:-BytecodeVerificationRemote`, from the command line, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` or `_JAVA_OPTIONS`); instrumentation is off |
| `degraded` | `bytecode_verification_off` | the same, but the app turned instrumentation on itself |

## JVMs without bytecode verification

With verification off, the JVM never checks the classes the agent rewrites. A rewrite it would normally reject
with a `VerifyError` reaches the JIT compiler instead, and can crash the whole JVM (for example
`guarantee(_stack_height < _max_stack) failed: stack overflow` in the bytecode escape analyzer). So in such a JVM
the distribution turns all instrumentation off by default: `otel.instrumentation.common.default-enabled=false`,
or `distribution.javaagent.instrumentation.default_enabled: false` under a config file. The SDK still starts and
reports its status.

- The KloudMate Agent doesn't load the distribution into these JVMs at all: the manifest's deny list holds the
  same flags.
- To instrument such a JVM anyway, set `OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED=true` (or
  `-Dotel.instrumentation.common.default-enabled=true`, or `default_enabled: true` in the file). Removing
  `-Xverify:none` is the better fix: it has been deprecated since Java 13.

The check reads `/proc/self/cmdline` and never touches JMX or `java.util.logging` during agent startup. Both
break applications that install their own `LogManager`, such as JBoss and WildFly. Any failure in the extension
logs one `[kloudmate-java]` line on stderr, and the agent continues with upstream behaviour.

## Limitations

- The Java OTLP exporters have no Unix-socket transport, so `agent.otlp_socket` is skipped and TCP is used.
- Planned: remote configuration over OpAMP, continuous profiling, and a config-file handoff for JVMs attached at
  runtime.

## Development

```sh
gradle jar test        # extension jar (Java 8 bytecode) and unit tests
smoke/run.sh           # end-to-end against a local OTLP sink (JDK, Gradle, Go)
release.sh             # reproducible jar, km-manifest.json, SHA256SUMS
```

The smoke test runs three scenarios:

1. KloudMate defaults;
2. your own `OTEL_*` settings win;
3. a config file with exporters that have no endpoint.
