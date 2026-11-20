# km-manifest.json

Every release ships a `km-manifest.json` next to its artifacts. It says where a distribution can run and how it
is activated, so tooling (the KloudMate Agent, Kubernetes admission, your own scripts) can decide whether to load
it into a process before anything happens there.

Schema: [`km-manifest.schema.json`](km-manifest.schema.json) (JSON Schema 2020-12).

| Field | Purpose |
| --- | --- |
| `distro` | Name and version, plus the exact upstream component and version it was tested with |
| `runtime` | Runtime name and the supported version range |
| `platforms` | OS, architecture and libc combinations the release was built and tested for (with `min_glibc` where it matters) |
| `deny` | Executables, command lines and environment variables the distribution must never load into: package managers, build tools, `java -version`, processes already running another vendor's agent |
| `entry` | How the runtime loads it (`JAVA_TOOL_OPTIONS`, `NODE_OPTIONS`, `PYTHONPATH`, CLR profiler, PHP ini), extra environment it needs, and whether it reads `OTEL_CONFIG_FILE` |
| `artifacts` | Every file with its sha256 |
| `provenance` | Source repository, commit, build time, attestation, licenses |

Validate one:

```sh
python3 -c 'import json,jsonschema,sys; jsonschema.validate(json.load(open(sys.argv[2])), json.load(open(sys.argv[1])))' \
  manifest/km-manifest.schema.json java/build/release/km-manifest.json
```

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
    "cmdline": ["* -version", "*org.gradle.launcher.daemon*", "*sbt-launch*"],
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
    "built_at": "2026-11-20T00:00:00Z",
    "licenses": ["Apache-2.0"]
  }
}
```

Each language's own `km-manifest.json` sits in its directory. Its `release.sh` fills in the real sha256s, commit
and build time.
