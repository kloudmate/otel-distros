# KloudMate PHP 8 distro

A **composer meta-package**, `kloudmate/opentelemetry-php`, on top of the official `opentelemetry` extension.
Scope is PHP 8.2–8.5 where a native build is bundled, since the official extension observes internal functions from 8.2.
Everything else (7.0–8.1, ZTS, musl, 8.6) uses KloudMate's own
tracer instead ([kloudmate/kloudmate-php](https://github.com/kloudmate/kloudmate-php), plan decision 2).

**Upstream base:** `opentelemetry` ext **1.2.1** and the SDK/contrib set in `scripts/php-otel-bundle/composer.lock`
(`sdk 1.14.0`, `opentelemetry-auto-pdo 0.4.0`, `auto-psr15 1.2.0`, `auto-psr18 1.2.0`, `sem-conv 1.38.0`). The
package is scoped (php-scoper) as the bundle is today, so it can't collide with the app's own composer tree.

## What it adds over upstream

| Area | Upstream | KloudMate distro |
| --- | --- | --- |
| Packages | pick-your-own contrib set | one `require` that pins the tested set (PDO, PSR-15/18, curl, Laravel, Symfony, Redis) |
| Resource | env | `km.service_id`, `telemetry.distro.*` |
| Config | env; declarative config through `OTEL_EXPERIMENTAL_CONFIG_FILE` | generated file plus `distribution.kloudmate` |
| Export | OTLP http to `:4318` | prefers km-agent's OTLP Unix socket when present |
| Span contract | contrib names | unchanged; it's the reference the 7.x tracer copies |

## Layout (planned)

```
php/
  composer.json
  src/{Bootstrap,ResourceDetector,Discovery}.php
  km-manifest.json
```
