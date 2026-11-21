# Distribution specification

What every KloudMate distribution implements, whatever the language. Language READMEs list where a runtime
differs.

## 1. Configuration file

When `OTEL_CONFIG_FILE` points at an OpenTelemetry declarative configuration file, the distribution uses it as
the source of truth, through the upstream SDK's own loader. KloudMate-specific settings live under
`distribution.kloudmate`:

```yaml
distribution:
  kloudmate:
    service_id: "systemd:checkout.service"       # becomes km.service_id
    agent:
      otlp_socket: /var/run/kmagent/otlp.sock     # used first, where the language's exporter supports Unix sockets
      otlp_endpoint: http://127.0.0.1:4318        # otherwise
    runtime_metrics: { profile: standard }        # standard | extended | off
    status_dir: /run/kmagent/sdk-status
```

Rules:

- Unknown keys under `distribution.kloudmate` are ignored, so newer files work with older distributions.
- The distribution only **fills** what the file leaves unset: an OTLP exporter without an `endpoint` gets the
  agent endpoint; a resource without `km.service_id` gets it. A value the file sets is never replaced.
- A file that can't be loaded never stops the application. The application runs uninstrumented and the status
  file reports `error` / `config_parse_error`.

Without a config file, the distribution reads the standard `OTEL_*` environment variables and applies its
defaults underneath them.

## 2. `km.service_id`

- A stable service identifier assigned by KloudMate (`systemd:<unit>`, `docker:<name>`, `pm2:<name>`, ...). It
  links a service across discovery, configuration, health and telemetry.
- Sources, first match wins: `distribution.kloudmate.service_id`, then `KM_SERVICE_ID`.
- Added to the resource of every signal only when the resource doesn't already carry `km.service_id` (for
  example from `OTEL_RESOURCE_ATTRIBUTES`). A value that is already set is never rewritten.
- It isn't a display name. `service.name` stays whatever the application or its configuration says.

## 3. Precedence

Lowest to highest:

1. upstream SDK defaults
2. KloudMate defaults
3. the configuration file
4. remote settings (planned, see §4; in memory only)
5. **any setting the application sets itself** (`OTEL_*` environment variables or the language's equivalent,
   such as Java system properties)

A generated configuration file refers to the application's variables through `${OTEL_...:-default}`
substitutions, so the application's values win inside the file too. Each effective value records where it came
from (`sdk_default`, `distro_default`, `file`, `remote`, `app_env`).

## 4. Remote configuration (planned)

An OpAMP client connects to the local KloudMate Agent. It reports health and effective configuration, and
accepts these live settings:

| Key | Type |
| --- | --- |
| `logging_level` | `trace` \| `debug` \| `info` \| `warn` \| `error` \| `off` |
| `sampling_rate` | float 0–1 (parent-based ratio) |
| `send_traces` / `send_metrics` / `send_logs` | bool |
| `deactivate_instrumentations` | comma-separated list |
| `deactivate_all_instrumentations` | bool |
| `metrics_profile` | `standard` \| `extended` \| `off` |

A remote setting never overrides one the application sets itself; such keys are reported as
`overridden_by_app`. Endpoint changes and newly enabled instrumentations need a restart.

## 5. Status file

At startup, before the SDK starts, each distribution writes `<status_dir>/<pid>.json`:

- `status_dir` comes from `distribution.kloudmate.status_dir`, else `KM_SDK_STATUS_DIR`, else
  `/run/kmagent/sdk-status`.
- The file is written only into an existing directory, and replaced atomically.
- The check that writes it never throws. Where possible it is written in syntax that the runtime versions it
  rejects can still parse.

```json
{
  "schema_version": 1,
  "pid": 1234,
  "km.service_id": "systemd:checkout.service",
  "distro": { "name": "kloudmate-python", "version": "0.1.0" },
  "result": "ok",
  "reason": "",
  "started_at": "2026-11-19T10:00:00Z",
  "heartbeat_at": "2026-11-19T10:00:00Z",
  "config_hash": "sha256:…",
  "effective_config": {
    "OTEL_EXPORTER_OTLP_ENDPOINT": { "value": "http://127.0.0.1:4318", "origin": "distro_default" },
    "OTEL_SERVICE_NAME": { "value": "checkout", "origin": "app_env" }
  },
  "overridden_by_app": [],
  "instrumentations": []
}
```

- `overridden_by_app` lists, sorted, the KloudMate-managed settings the application replaced with its own value:
  a KloudMate default it set itself (environment mode), or a `${OTEL_*}` substitution in the config file whose
  variable it sets (file mode). Each of them also appears in `effective_config` with origin `app_env`.
- `instrumentations` lists the instrumentations the distribution activated, where the language can tell. A
  distribution may rewrite the file as they load.

| `result` | Meaning |
| --- | --- |
| `ok` | running as configured |
| `degraded` | running, with a caveat in `reason` (e.g. `foreign_agent:ddtrace`) |
| `disabled` | switched off on purpose (e.g. `sdk_disabled`) |
| `unsupported_runtime` | runtime outside the supported range (e.g. `python_too_old`); the application runs untouched |
| `denied` | the process matched the manifest's deny list |
| `error` | the distribution failed (e.g. `config_parse_error`); the application still runs |

`reason` is a short machine-readable code. Each language README lists its own codes.
