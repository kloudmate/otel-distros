# KloudMate OpenTelemetry Distributions

KloudMate's distributions of the OpenTelemetry SDKs and zero-code agents. Each one is a thin layer on top of the
official upstream OpenTelemetry project for its language: same instrumentation, same APIs, same configuration.
KloudMate adds sensible defaults, service identity and health reporting on top.

**OpenTelemetry native.** Everything here speaks plain OTLP and follows the current OpenTelemetry semantic
conventions. To send data somewhere else, change the endpoint. There's nothing to rip out.

## Why use a KloudMate distribution

| | Upstream OpenTelemetry | KloudMate distribution |
| --- | --- | --- |
| Instrumentation | ✅ | ✅ the same upstream instrumentation, unmodified |
| Export defaults | per-language (gRPC in some, HTTP in others) | OTLP over HTTP/protobuf to the local KloudMate Agent, `127.0.0.1:4318` |
| Context propagation | W3C Trace Context + Baggage | W3C Trace Context + Baggage |
| Runtime metrics | varies; often off or partial | on by default (JVM, CPython, Node.js, CLR) |
| Service identity | `service.name` | adds `km.service_id`, a stable id that links a service across discovery, config and telemetry |
| Distro identity | upstream `telemetry.distro.*` | `telemetry.distro.name` / `telemetry.distro.version` name the KloudMate distribution |
| Config file | supported where the SDK supports it | also reads the KloudMate settings block (`distribution.kloudmate`) |
| Health | silent when something is wrong | startup check reports `ok` / `degraded` / `disabled` and the reason |
| Failure mode | depends on the SDK | never stops your app: any problem is logged once and the app runs on upstream defaults |

## Supported languages

| Language | How it loads | Built on (upstream) | Minimum runtime | Platforms | Status | Docs |
| --- | --- | --- | --- | --- | --- | --- |
| Java | Extension jar for the OpenTelemetry Java agent | `opentelemetry-javaagent` 2.34.0 | Java 8 | Linux x86-64, arm64 (any JVM vendor) | **Preview (0.2.0)** | [java/](java/) |
| Python | Distro package (`opentelemetry_distro` entry point) + `sitecustomize` bootstrap | `opentelemetry-sdk` 1.43.0, `opentelemetry-instrumentation` 0.64b0 | CPython 3.10 | Linux x86-64, arm64 (glibc) | **Preview (0.1.0)** | [python/](python/) |
| Node.js | `--require` / `--import` register hook | `@opentelemetry/auto-instrumentations-node` 0.77.0, `sdk-node` 0.219.0 | Node.js 18.19 or 20.6 | Linux x86-64, arm64 | **Preview (0.1.0)** | [nodejs/](nodejs/) |
| .NET | Plugin for OpenTelemetry .NET automatic instrumentation | `opentelemetry-dotnet-instrumentation` 1.15.0 | .NET 8 (.NET Framework 4.6.2 on Windows: built, not yet tested) | Linux x86-64, arm64 (glibc) | **Preview (0.1.0)** | [dotnet/](dotnet/) |
| PHP | KloudMate PHP tracer, shipped with the KloudMate Agent | own tracer, OTLP output | PHP 7.0 | Linux x86-64, arm64 (glibc, musl) | Available via the agent | [php/](php/) |

Minimum runtime is the oldest version the distribution is built and tested for. Inside that range, what gets
instrumented is decided by the upstream project: see each upstream project's list of supported libraries.

## Getting started

### With the KloudMate Agent (recommended)

The KloudMate Agent finds services on the host or in your containers and attaches the right distribution for each
one. You don't change your code, images or start commands. Turn on APM for a service in KloudMate and it starts
sending traces, metrics and logs.

### Manually

Download a release (see [Releases](#releases)), check it against `SHA256SUMS`, and load it the way you'd load the
upstream agent. For Java:

```sh
java -javaagent:/opt/otel/opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=/opt/otel/kloudmate-otel-extension.jar \
     -Dotel.service.name=checkout \
     -jar app.jar
```

Each language directory explains its own loading steps.

## Configuration

Every standard OpenTelemetry setting works as documented upstream: `OTEL_*` environment variables, the language's
system properties, and the declarative config file (`OTEL_CONFIG_FILE`).

**Your settings always win.** Settings are applied in this order, lowest to highest:

1. upstream SDK defaults
2. KloudMate defaults
3. the config file (`OTEL_CONFIG_FILE`)
4. anything you set yourself in the app's environment or command line

So if you already set `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME` or `OTEL_RESOURCE_ATTRIBUTES`, the
distribution keeps your values.

### KloudMate settings

| Setting | Where | Default | What it does |
| --- | --- | --- | --- |
| `KM_SERVICE_ID` | env | unset | Value of `km.service_id`, if the resource doesn't already have one |
| `KM_SDK_STATUS_DIR` | env | `/run/kmagent/sdk-status` | Where the startup status file is written (only if the directory exists) |
| `distribution.kloudmate.service_id` | config file | unset | Same as `KM_SERVICE_ID`; takes precedence over it |
| `distribution.kloudmate.agent.otlp_endpoint` | config file | `http://127.0.0.1:4318` | Endpoint used for exporters that don't set their own |
| `distribution.kloudmate.agent.otlp_socket` | config file | unset | Unix socket of the local agent, used first where the language's exporter supports it |
| `distribution.kloudmate.runtime_metrics.profile` | config file | `standard` | `standard`, `extended` or `off` |
| `distribution.kloudmate.status_dir` | config file | see `KM_SDK_STATUS_DIR` | Where the startup status file is written |

Example config file:

```yaml
file_format: "1.0"
resource:
  attributes:
    - name: service.name
      value: ${OTEL_SERVICE_NAME:-checkout}
tracer_provider:
  processors:
    - batch:
        exporter:
          otlp_http: {}        # endpoint filled from distribution.kloudmate.agent
distribution:
  kloudmate:
    service_id: "systemd:checkout.service"
    agent:
      otlp_endpoint: http://127.0.0.1:4318
    runtime_metrics: { profile: standard }
```

Unknown keys under `distribution.kloudmate` are ignored, so a newer config file works with an older distribution.
The full specification is in [contract/](contract/).

## Resource attributes

| Attribute | Value |
| --- | --- |
| `km.service_id` | Stable service id from KloudMate, e.g. `systemd:checkout.service`. Never overwritten if already set |
| `telemetry.distro.name` | `kloudmate-java`, `kloudmate-python`, `kloudmate-nodejs`, `kloudmate-dotnet` |
| `telemetry.distro.version` | The distribution's version |

`service.name` and all other attributes come from your configuration and the upstream resource detectors, unchanged.

## Health and status

At startup each distribution writes `<status_dir>/<pid>.json`. The file holds:

- the result: `ok`, `degraded`, `disabled`, `unsupported_runtime`, `denied` or `error`;
- a short reason, such as `foreign_agent:ddtrace` or `sdk_disabled`;
- a hash of the config file;
- the effective value of each KloudMate-managed setting, and where it came from (`distro_default`, `file` or
  `app_env`).

The KloudMate Agent reads these files to show per-service health. The check never throws and never stops the app.

## Releases

Each language is versioned and released on its own, with tags such as `java/v0.1.0` and `python/v0.1.0`. A release
contains:

- the artifact (jar, wheel, npm package or zip);
- `km-manifest.json`, which lists supported runtime versions and platforms, the processes the distribution must
  never load into, and how it's activated (schema in [manifest/](manifest/));
- `SHA256SUMS`.

Each release is tested against one exact upstream version, which its manifest records. Builds are reproducible.

To release a language:

1. bump `distro.version` in its `km-manifest.json`, and in its package file;
2. run the **release** workflow (Actions → release → Run workflow) for that language.

It builds with the language's `release.sh`, checks the manifest and the checksums, and publishes the GitHub
release. Once the repository is public, it also attaches build provenance.

## Building and testing

| Language | Build | Test | Requires |
| --- | --- | --- | --- |
| Java | `cd java && gradle jar` | `gradle test` and `java/smoke/run.sh` | JDK 8+, Gradle 8, Go (smoke sink) |
| Python | `python/release.sh` | `pytest python/tests` and `python/smoke/run.sh` | CPython 3.10+, Go (smoke sink); Docker optional |
| Node.js | `nodejs/release.sh` | `npm test` and `nodejs/smoke/run.sh` | Node.js 18.19+ with npm, Go (smoke sink); Docker optional |
| .NET | `dotnet/release.sh` | `dotnet/smoke/run.sh` | Docker (the .NET SDK runs in a container), Go (smoke sink) |

The smoke tests run a sample app against [`smoke/otlpsink`](smoke/otlpsink), a small local OTLP/HTTP receiver. They
check three things:

- the KloudMate defaults and resource attributes;
- that the app's own OpenTelemetry settings still win;
- that the config file is read.

## Contributing

- Fixes to instrumentation belong upstream in the OpenTelemetry project: these distributions never patch or fork
  it.
- Keep changes generic and test them for every supported language, not only the one where the bug showed up.
- Run the language's smoke test before opening a pull request.

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). The upstream OpenTelemetry components keep their own licenses (Apache 2.0).
