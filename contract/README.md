# The KloudMate distro contract

What every distro implements, and what km-agent guarantees in return. Sources: plan §3 and Phases 2–3,
research 03 §8.2 and research 04 (Elastic's OpAMP central config).

## 1. Generated declarative config

km-agent renders one file per service, `/etc/kmagent/sdk/<service>-<sha8>.yaml`, in OpenTelemetry declarative
config format (`file_format: "1.2"`; `"1.1"` for runtimes that don't read 1.2 yet). The activation sets only the
entry hook plus `OTEL_CONFIG_FILE` and `KM_SERVICE_ID`. Node, and services that already run their own OTel
agent, stay on env.

KloudMate-only settings live under `distribution.kloudmate`:

```yaml
distribution:
  kloudmate:
    service_id: "systemd:checkout.service"       # == km.service_id
    agent:
      otlp_socket: /var/run/kmagent/otlp.sock     # preferred when present
      otlp_endpoint: http://127.0.0.1:4318        # fallback
    opamp:
      endpoint: http://127.0.0.1:4320/v1/opamp
      polling_interval: 30s
    runtime_metrics: { profile: standard }        # standard | extended | off
    profiler: { enabled: false }
    status_dir: /run/kmagent/sdk-status
```

Distros must ignore unknown keys under `distribution.kloudmate` (forward compatibility).

## 2. `km.service_id`

- The stable id km-agent's discovery assigns (`systemd:<unit>`, `docker:<name>`, `pm2:<name>`, ...). It's the join
  key between discovery, config, status and telemetry.
- Set as a resource attribute on every signal. Source order: `distribution.kloudmate.service_id`, then
  `KM_SERVICE_ID`, then `km.service_id` in `OTEL_RESOURCE_ATTRIBUTES`.
- The collector's `kmserviceidentity` processor already relies on it; distros must never rewrite it.
- `service.name` stays whatever the app or km-agent says; `km.service_id` is not a display name.

## 3. Precedence (ADR-0002: the app's own values always win)

Lowest to highest:

1. SDK defaults
2. distro defaults
3. generated file
4. live OpAMP settings (in memory, not persisted)
5. **any key the app sets itself in its environment** (`OTEL_*`, or the equivalent system property)

The generated file references the app's `OTEL_*` through `${VAR:-default}` substitutions, so the app's value wins
inside the file as well. A live setting never overrides an app-set key. The distro records the origin of each
effective value (`sdk_default`, `distro_default`, `file`, `opamp`, `app_env`) and reports a key it couldn't change
as `overridden_by_app`, so the UI can say why a remote change didn't apply.

## 4. OpAMP (Phase 3)

Server: embedded in km-agent on `127.0.0.1:4320` (later a UDS bound to the client's PID). Client identity:
identifying `service.name`, `service.namespace`, `service.instance.id`; non-identifying `km.service_id`,
`process.pid`, `telemetry.sdk.*`, `telemetry.distro.*`.

Capabilities: `ReportsEffectiveConfig`, `AcceptsRemoteConfig`, `ReportsRemoteConfig`, `ReportsHealth`,
`ReportsHeartbeat`.

Remote config is served under the `kloudmate` key, and the same body under `elastic`, so stock EDOT SDKs work
unmodified. Keys (Elastic central-config names):

| Key | Type | Live? |
| --- | --- | --- |
| `logging_level` | `trace\|debug\|info\|warn\|error\|off` | yes |
| `sampling_rate` | float 0–1 (parent-based ratio) | yes |
| `send_traces` / `send_metrics` / `send_logs` | bool | yes |
| `deactivate_instrumentations` | comma list | yes (pinned SDK versions only) |
| `deactivate_all_instrumentations` | bool | yes |
| `metrics_profile` | `standard\|extended\|off` | yes |

Endpoint changes and new instrumentations still need a restart. Reply `APPLIED` or `FAILED` with the config hash.

## 4a. PHP tracer: settings file instead of OpAMP

PHP-FPM runs many short-lived workers per pool. An OpAMP connection or poll per worker
buys nothing for settings that rarely change, so the KloudMate PHP tracer
(kloudmate/kloudmate-php) is managed through a file km-agent writes and every worker
re-reads within a second of a change, with no reload:

- Host: `/opt/kmagent/agents/php/km/settings/<unit>.conf`; container: `/opt/kloudmate/settings.conf`.
  The ini points at it with `kloudmate.settings_file`.
- `key=value` lines: `version` (a content hash), `enabled`, `sample_rate` (head sampling
  for roots; the threshold goes into tracestate as `ot=th:`). Unknown keys are ignored.
  Turning single instrumentations off is not a setting: the app uses the OpenTelemetry
  variables (`OTEL_SDK_DISABLED`, `OTEL_PHP_DISABLED_INSTRUMENTATIONS`), as with the OTel PHP SDK.
- Written atomically and only when the content changes.
- Applied status: the tracer reports `kloudmate.settings.version` (and any
  `kloudmate.settings.error`) on every root span. km-agent presents this in the same
  shape as OpAMP health and effective config.

## 5. Status reporting

Every distro reports, through OpAMP when connected, else a file `/run/kmagent/sdk-status/<pid>.json`
(New Relic-style health file). The directory is `distribution.kloudmate.status_dir`, else `KM_SDK_STATUS_DIR`, else
the default; distros write only into an existing directory (km-agent creates it) and replace the file atomically:

```json
{
  "schema_version": 1,
  "pid": 1234,
  "km.service_id": "systemd:checkout.service",
  "distro": { "name": "kloudmate-python", "version": "0.1.0" },
  "result": "ok",
  "reason": "",
  "started_at": "2026-11-19T10:00:00Z",
  "heartbeat_at": "2026-11-19T10:05:00Z",
  "config_hash": "sha256:…",
  "effective_config": { "sampling_rate": { "value": 0.25, "origin": "opamp" },
                        "OTEL_SERVICE_NAME": { "value": "checkout", "origin": "app_env" } },
  "overridden_by_app": ["sampling_rate"],
  "instrumentations": ["flask", "requests", "psycopg2"]
}
```

`result` is one of `ok`, `degraded`, `disabled`, `unsupported_runtime`, `denied`, `error`. `reason` is a short
machine-readable code (`python_too_old`, `foreign_agent:ddtrace`, `config_parse_error`, ...). The startup check
writes this before the SDK starts, in syntax old enough to parse on the versions it rejects, and never throws.
