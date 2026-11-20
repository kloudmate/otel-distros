# KloudMate Node.js distro

A **register wrapper**, `@kloudmate/opentelemetry-node`, loaded with `--require` (CJS) or `--import` (ESM):

```
NODE_OPTIONS="--require /opt/kmagent/agents/node/stable/register.js"
```

**Upstream base:** `@opentelemetry/auto-instrumentations-node` **0.77.0** (`scripts/otel-agent-bundles/node/package.json`;
upstream is at 0.83.1, bump tracked as plan 0.4). The wrapper replaces today's `otel.js` / `otel.mjs`.

## Important: `register` ignores the config file

`@opentelemetry/auto-instrumentations-node/register` builds its SDK from env vars only. It **does not read the
declarative config file** (`OTEL_CONFIG_FILE`); only `startNodeSdk()` does. So the wrapper must not just re-export
`register`. It calls `startNodeSdk()` itself, guarded, and falls back to env when no file is set. Until the
Node SDK's declarative config support is stable, km-agent keeps Node on env (plan Phase 2).

## What it adds over upstream

| Area | Upstream 0.77.0 | KloudMate distro |
| --- | --- | --- |
| Runtime metrics | not enabled by `register` | `@opentelemetry/instrumentation-runtime-node` on |
| Resource | env | `km.service_id`, `telemetry.distro.*` |
| Config | env only (via `register`) | `startNodeSdk()` with the generated file when set, env otherwise |
| Safety | throws on bad config | every step in `try/catch`; the app always starts |
| Live config | none | `@elastic/opamp-client-node` (Apache-2.0) to the local OpAMP server, Phase 3 |
| Profiling | none | optional `@pyroscope/nodejs`, off by default |

## Layout (planned)

```
nodejs/
  package.json
  register.js  register.mjs
  src/{sdk,resource,discovery,status}.js
  km-manifest.json
```
