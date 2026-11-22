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

### The agent's socket

The KloudMate Agent's socket speaks OTLP/gRPC. Its path comes from `agent.otlp_socket`, else `KM_OTLP_SOCKET`. A
distribution uses it only when all of these hold:

- the path is absolute and a socket is there (.NET connects once to check);
- the language can send OTLP/gRPC over a Unix socket (Python only when `opentelemetry-exporter-otlp-proto-grpc`
  is installed; never Java);
- the application set no `OTEL_EXPORTER_OTLP_[<SIGNAL>_]ENDPOINT` or `..._PROTOCOL` itself.

Otherwise TCP `otlp_endpoint` is used. Without a config file, every signal moves to the socket
(`OTEL_EXPORTER_OTLP_PROTOCOL=grpc`, endpoint `unix://<path>`, no TLS). With a config file, only an `otlp_grpc`
exporter without an `endpoint` gets the socket: the file names the protocol, and the socket doesn't speak
OTLP/HTTP.

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
4. remote settings (§4; in memory only)
5. **any setting the application sets itself** (`OTEL_*` environment variables or the language's equivalent,
   such as Java system properties)

A generated configuration file refers to the application's variables through `${OTEL_...:-default}`
substitutions, so the application's values win inside the file too. Each effective value records where it came
from (`sdk_default`, `distro_default`, `file`, `remote`, `app_env`).

## 4. Remote configuration

An OpAMP client reports health and the startup status to the local KloudMate Agent, and applies the remote
settings that can change while the process runs. It is off unless an endpoint is configured:
`distribution.kloudmate.agent.opamp_endpoint`, else `KM_OPAMP_ENDPOINT` (for Java at runtime attach,
`kloudmate.agent.opamp_endpoint` in the attach file).

Transport: OpAMP over plain HTTP. The client POSTs an `AgentToServer` message (`application/x-protobuf`) every
`KM_OPAMP_POLL_INTERVAL` seconds (default 30, with 10% jitter), and the response is a `ServerToAgent` message. The
client's own requests are never traced.

Each message carries:

- `instance_uid`: a UUIDv7 made at startup, new in a forked child;
- `capabilities`: ReportsStatus, AcceptsRemoteConfig, ReportsEffectiveConfig, ReportsHealth, ReportsRemoteConfig;
- `health`: `healthy` unless the status result is `disabled`, `denied`, `unsupported_runtime` or `error`; `status`
  is the result, `last_error` the reason;
- `effective_config`: the status file's JSON (§5) under the key `kloudmate.status`;
- `remote_config_status` once a remote config has arrived;
- `agent_description` in the first message and whenever the server sets `ReportFullState`: identifying
  `service.name`, `service.instance.id` (the `instance_uid`) and `km.service_id`; non-identifying
  `telemetry.distro.name`, `telemetry.distro.version`, `process.pid` and `os.type`.

The server sends settings as a JSON object under the remote config key `kloudmate`:

| Key | Type | Applied |
| --- | --- | --- |
| `sampling_rate` | number 0–1 (parent-based ratio) | live |
| `send_traces` | bool; `false` drops every span | live |
| `logging_level` | `trace` \| `debug` \| `info` \| `warn` \| `error` \| `off` | not yet |
| `send_metrics` / `send_logs` | bool | not yet |
| `deactivate_instrumentations` | comma-separated list | not yet |
| `deactivate_all_instrumentations` | bool | not yet |
| `metrics_profile` | `standard` \| `extended` \| `off` | not yet |

- A config whose hash the client has already applied is ignored. An empty object returns to the configured
  behaviour; a config that is not sent leaves the last one in place.
- An invalid value fails the whole config (`FAILED`, with the reason in `error_message`), and nothing changes.
- Otherwise the status is `APPLIED`, and `error_message` lists the keys set by the application and the keys not
  applied live. Unknown keys are ignored.
- A remote setting never overrides one the application sets itself: `sampling_rate` gives way to
  `OTEL_TRACES_SAMPLER` or `OTEL_TRACES_SAMPLER_ARG`, and `send_traces` to `OTEL_TRACES_EXPORTER`. Such keys are
  listed in `overridden_by_app`.
- Applied keys appear in the status's `effective_config` with origin `remote`, and the status file is rewritten.
  Remote settings live in memory only: a restart starts from the configured values.

Where a runtime can't take the live keys, the distribution reports them as not applied live; see each language's
README.

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
