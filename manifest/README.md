# km-manifest.json

Every distro release ships a `km-manifest.json` next to its artifact. km-agent reads it at apply time and
km-injector reads it at admission, so both make the same decision: does this distro run in this process, and
how is it activated. It replaces the Go switches in `internal/instrumentation/vm/dropin.go` and `vm/python.go`
(plan Phase 2), modelled on Datadog's `requirements.json` and Odigos's distro catalogue (research 01, 05).

Schema: [`km-manifest.schema.json`](km-manifest.schema.json) (JSON Schema 2020-12).

| Field | Purpose |
| --- | --- |
| `distro` | name, version, and the exact upstream component/version it layers on |
| `runtime` | runtime name, supported version range, PHP flavors and SAPIs |
| `platforms` | os/arch/libc cells built and tested, with `min_glibc` where it matters (CentOS 7 is 2.17) |
| `deny` | exe and cmdline globs and foreign-agent env vars that must never get the distro (npm, `java -version`, build tools) |
| `entry` | activation hook (`JAVA_TOOL_OPTIONS`, `NODE_OPTIONS`, `PYTHONPATH`, CLR profiler, PHP ini) and whether the runtime reads the config file |
| `artifacts` | each file with its sha256 (and PHP module API for `.so` cells) |
| `provenance` | source repo, commit, build time, SLSA attestation, licenses |

## Example (Java)

```json
{
  "schema_version": 1,
  "distro": {
    "name": "kloudmate-java",
    "version": "0.1.0",
    "upstream": { "component": "opentelemetry-javaagent", "version": "2.29.0" }
  },
  "runtime": { "name": "java", "version_range": ">=8" },
  "platforms": [
    { "os": "linux", "arch": "amd64", "libc": "any" },
    { "os": "linux", "arch": "arm64", "libc": "any" }
  ],
  "deny": {
    "exe": ["**/jdk*/bin/javac", "**/jshell"],
    "cmdline": ["* -version", "*org.gradle.launcher.daemon*", "*org.codehaus.plexus.classworlds.launcher.Launcher*", "*sbt-launch*"],
    "env_present": ["DD_TRACE_ENABLED", "NEW_RELIC_LICENSE_KEY"]
  },
  "entry": {
    "kind": "java_tool_options",
    "value": "-javaagent:${INSTALL_DIR}/opentelemetry-javaagent.jar -Dotel.javaagent.extensions=${INSTALL_DIR}/kloudmate-otel-extension.jar",
    "reads_config_file": true
  },
  "artifacts": [
    { "path": "opentelemetry-javaagent.jar", "sha256": "546531ca690a8603d2923b6db26bbda35c6409327b1e610430ae33c2f8f68050" },
    { "path": "kloudmate-otel-extension.jar", "sha256": "0000000000000000000000000000000000000000000000000000000000000000" }
  ],
  "provenance": {
    "source_repo": "https://github.com/kloudmate/otel-distros",
    "commit": "0000000000000000000000000000000000000000",
    "built_at": "2026-11-19T00:00:00Z",
    "licenses": ["Apache-2.0"]
  }
}
```

## Example (PHP 7.x tracer cell)

```json
{
  "schema_version": 1,
  "distro": {
    "name": "kloudmate-php",
    "version": "0.1.0",
    "upstream": { "component": "kloudmate-php", "version": "0.1.0" }
  },
  "runtime": { "name": "php", "version_range": ">=7.0 <8.2", "flavors": ["nts"], "sapis": ["fpm-fcgi", "apache2handler"] },
  "platforms": [{ "os": "linux", "arch": "amd64", "libc": "glibc", "min_glibc": "2.17" }],
  "entry": { "kind": "php_ini", "value": "extension=${INSTALL_DIR}/ext/20190902/nts/amd64/kloudmate_php.so" },
  "artifacts": [
    { "path": "ext/20190902/nts/amd64/kloudmate_php.so", "abi": "20190902", "platform": 0,
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000" }
  ],
  "provenance": {
    "source_repo": "https://github.com/kloudmate/kloudmate-php",
    "commit": "0000000000000000000000000000000000000000",
    "built_at": "2026-11-19T00:00:00Z",
    "licenses": ["Apache-2.0", "MulanPSL-2.0"]
  }
}
```
