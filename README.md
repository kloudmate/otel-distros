# otel-distros

Thin KloudMate distributions of the upstream OpenTelemetry SDKs, one directory per language.

km-agent consumes released artifacts from here (pinned by version + sha256); it never builds this repo.

## Why

KloudMate's SDK support today is env vars rendered by `vm.InstrumentationEnv`, plus stock upstream bundles
fetched by `scripts/otel-agent-bundles/build.sh`. Every SDK quirk is hand-coded in Go, optional SDK features are
off, and nothing reports which config took effect (plan §1). The market converged on a thin layer on top of
upstream, never a fork of the instrumentation (plan §2). This repo is that layer.

Each distro adds, over the upstream version km-agent pins today:

- KloudMate defaults (propagators, exporter protocol, runtime metrics on, host metrics off);
- `km.service_id` and `telemetry.distro.{name,version}` on the resource;
- local-agent endpoint discovery (UDS first, then `127.0.0.1:4318`);
- reading the generated declarative config file and its `distribution.kloudmate` block ([`contract/`](contract/));
- an OpAMP client to km-agent's local server (Phase 3), reporting health and the effective config with origins;
- a startup check that never throws and reports `result`/`reason` to the agent;
- a `km-manifest.json` that tells km-agent where the distro can run ([`manifest/`](manifest/)).

It never changes upstream instrumentation code. If a fix belongs upstream, it goes upstream.

## Layout

| Dir | What | Upstream base (pinned in `scripts/otel-agent-bundles/build.sh` today) |
| --- | --- | --- |
| [`java/`](java/) | Extension jar loaded by the upstream Java agent (`-Dotel.javaagent.extensions`), Gradle | `opentelemetry-javaagent` 2.29.0 |
| [`python/`](python/) | Distro package registered through `opentelemetry_distro` / `opentelemetry_configurator` entry points | `opentelemetry-distro` 0.64b0, SDK 1.43.0 |
| [`nodejs/`](nodejs/) | `--require` register wrapper around `startNodeSdk()` | `@opentelemetry/auto-instrumentations-node` 0.77.0 |
| [`dotnet/`](dotnet/) | Plugin assembly (`OTEL_DOTNET_AUTO_PLUGINS`) | `opentelemetry-dotnet-instrumentation` 1.15.0 |
| [`php/`](php/) | Composer meta-package for PHP 8.2+ on the official extension | `opentelemetry` ext 1.2.1 (`scripts/php-otel-bundle`) |
| [`manifest/`](manifest/) | `km-manifest.json` JSON Schema and example | n/a |
| [`contract/`](contract/) | Config, OpAMP and status contract every distro implements | n/a |
| [`images/`](images/) | Kubernetes init images (one per language) for km-injector (plan Phase 5) | n/a |

PHP's tracer is not here: KloudMate's own tracer, [kloudmate/kloudmate-php](https://github.com/kloudmate/kloudmate-php),
covers PHP 7.0–8.6 (and every build the native path below doesn't, such as ZTS, musl and 8.6).

## Releases and how km-agent consumes them

1. Each language is versioned and tagged on its own: `java/v0.1.0`, `python/v0.1.0`, and so on. A release
   publishes the artifact (jar, wheel, npm tarball, zip, composer package), its `km-manifest.json` and a
   `SHA256SUMS` file, with provenance (SLSA build attestation from the release workflow).
2. km-agent pins each distro by **version + sha256** in `scripts/otel-agent-bundles/build.sh`, exactly as it pins
   the upstream artifacts today (`JAVA_AGENT_VERSION` / `JAVA_AGENT_SHA256` and friends). The build fails on a
   mismatch. The distro's `km-manifest.json` is copied next to the artifact under `/opt/kmagent/agents/<lang>/`.
3. km-agent reads the manifest at apply time instead of the hard-coded switches in `vm/dropin.go` and
   `vm/python.go` (plan Phase 2): runtime range, libc, arch, deny globs and the entry hook.
4. Install layout on the host: `/opt/kmagent/agents/<lang>/<version>/`, with `stable` and `experiment` symlinks
   swapped atomically, so a bad distro rolls back without a package downgrade.
5. Kubernetes uses the matching image from [`images/`](images/), pinned by digest.

km-agent keeps only activation and injection, config rendering, the OpAMP server, km-injector and the bundle
build script. Distro code doesn't live in km-agent.

## Status

| Language | Status | Test locally |
| --- | --- | --- |
| Java | 0.1.0 first release: defaults, resource, endpoint discovery, declarative config, startup check, manifest | `java/smoke/run.sh` |
| Python | scaffold | |
| Node.js | scaffold | |
| .NET | scaffold | |

Follow-ups for every language: OpAMP client (contract §4, plan Phase 3), Kubernetes init images (`images/`, Phase 5).

[`smoke/otlpsink`](smoke/otlpsink) is the OTLP/HTTP sink every smoke test exports to (its own Go module).
CI is builds plus the smoke tests, on manual trigger only.
