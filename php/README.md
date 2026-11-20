# PHP

PHP applications are instrumented through the KloudMate Agent, not through a package from this repository.

| | |
| --- | --- |
| Runtime | PHP 7.0 – 8.6, NTS and ZTS |
| Platforms | Linux x86-64, arm64 (glibc and musl) |
| SAPIs | PHP-FPM, Apache `mod_php` |
| Output | OTLP with current OpenTelemetry semantic conventions, `km.service_id` and `telemetry.distro.*` on the resource |

The agent picks one of two engines per PHP build:

- **PHP 8.2–8.5, NTS, glibc:** the official OpenTelemetry PHP extension and SDK, where a matching build is
  bundled.
- **Every other build** (7.x, 8.0, 8.1, ZTS, musl, 8.6): KloudMate's own native tracer, which produces the same
  spans and attributes.

Both are configured with the standard variables: `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES`,
`OTEL_SDK_DISABLED` and `OTEL_PHP_DISABLED_INSTRUMENTATIONS`. PHP-FPM workers are short-lived, so remote
settings (enabled, sampling) reach them through a small settings file that the agent writes. Every worker
re-reads it within a second of a change, without a reload.
