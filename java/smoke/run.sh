#!/usr/bin/env bash
# Local smoke test for the KloudMate Java distro: builds the extension and the OTLP sink, runs a sample
# app under the upstream agent + extension, and asserts what reaches the sink and the status file.
#   java/smoke/run.sh            (needs JDK 8+, Gradle, Go, curl; no Docker)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
AGENT_VERSION="${JAVA_AGENT_VERSION:-2.34.0}"
AGENT_SHA256="${JAVA_AGENT_SHA256:-da44154cea128257779c8a782b9651fd7c0c6aa177cd3810f9a867ac8509555d}"
WORK="${SMOKE_WORK:-$(mktemp -d)}"
CACHE="${SMOKE_CACHE:-$HOME/.cache/kloudmate-otel-distros}"
mkdir -p "$WORK" "$CACHE"
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

agent="$CACHE/opentelemetry-javaagent-$AGENT_VERSION.jar"
if [ ! -f "$agent" ]; then
  curl -fsSL --retry 3 -o "$agent.tmp" \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$AGENT_VERSION/opentelemetry-javaagent.jar"
  mv "$agent.tmp" "$agent"
fi
echo "$AGENT_SHA256  $agent" | sha256sum -c --quiet -

(cd "$ROOT/java" && gradle -q --no-daemon jar agentJar)
ext="$ROOT/java/build/libs/kloudmate-otel-extension.jar"
kmagent="$ROOT/java/build/libs/kloudmate-javaagent.jar"
(cd "$ROOT/smoke/otlpsink" && go build -o "$WORK/otlpsink" .)
javac -d "$WORK/app" "$HERE/App.java"
javac -d "$WORK/attach" "$HERE/Attach.java"

"$WORK/otlpsink" -listen 127.0.0.1:4318 -out "$WORK/default.jsonl" 2>"$WORK/sink-default.log" & PIDS+=($!)
"$WORK/otlpsink" -listen 127.0.0.1:14318 -out "$WORK/app.jsonl" 2>"$WORK/sink-app.log" & PIDS+=($!)
"$WORK/otlpsink" -listen 127.0.0.1:24318 -out "$WORK/file.jsonl" 2>"$WORK/sink-file.log" & PIDS+=($!)
"$WORK/otlpsink" -listen 127.0.0.1:34318 -out "$WORK/noverify.jsonl" 2>"$WORK/sink-noverify.log" & PIDS+=($!)
sleep 1

# Only the variables a scenario sets: the caller's OTEL_* and JAVA_TOOL_OPTIONS never leak in. JVM_FLAGS holds
# extra JVM options for one scenario.
JVM_FLAGS=()
run_app() {
  local name="$1"; shift
  mkdir -p "$WORK/status-$name"
  env -i PATH="$PATH" HOME="$HOME" KM_SDK_STATUS_DIR="$WORK/status-$name" \
    OTEL_METRIC_EXPORT_INTERVAL=1000 OTEL_BSP_SCHEDULE_DELAY=200 "$@" \
    java "${JVM_FLAGS[@]}" -javaagent:"$agent" -Dotel.javaagent.extensions="$ext" -cp "$WORK/app" App \
    >"$WORK/app-$name.log" 2>&1 || { cat "$WORK/app-$name.log"; exit 1; }
}

echo ">>> 1. env mode, KloudMate defaults (endpoint discovered, km.service_id from KM_SERVICE_ID)"
run_app defaults KM_SERVICE_ID=systemd:smoke.service OTEL_SERVICE_NAME=smoke-defaults

echo ">>> 2. env mode, the app's own OTEL_* win"
run_app appenv KM_SERVICE_ID=systemd:smoke.service OTEL_SERVICE_NAME=smoke-app \
  OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:14318 \
  OTEL_RESOURCE_ATTRIBUTES=km.service_id=app-own,deployment.environment.name=prod \
  SMOKE_TARGET=http://127.0.0.1:14318/health

echo ">>> 3. declarative config file with distribution.kloudmate"
run_app file OTEL_CONFIG_FILE="$HERE/config.yaml" SMOKE_FILE_PORT=24318 KM_SERVICE_ID=ignored-by-block \
  SMOKE_TARGET=http://127.0.0.1:24318/health

echo ">>> 4. a JVM without bytecode verification: no instrumentation, status denied"
NV=(OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:34318 SMOKE_TARGET=http://127.0.0.1:34318/health)
JVM_FLAGS=(-Xverify:none)
run_app noverify KM_SERVICE_ID=systemd:noverify.service OTEL_SERVICE_NAME=smoke-noverify "${NV[@]}"

echo ">>> 5. ... unless the app turns instrumentation on itself (verification off through JAVA_TOOL_OPTIONS)"
JVM_FLAGS=()
run_app noverify-forced KM_SERVICE_ID=systemd:noverify.service OTEL_SERVICE_NAME=smoke-noverify-forced "${NV[@]}" \
  JAVA_TOOL_OPTIONS=-Xverify:none OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED=true

echo ">>> 6. a config file in a JVM without bytecode verification"
JVM_FLAGS=(-noverify)
run_app noverify-file OTEL_CONFIG_FILE="$HERE/config.yaml" SMOKE_FILE_PORT=34318 OTEL_SERVICE_NAME=smoke-noverify-file \
  SMOKE_TARGET=http://127.0.0.1:34318/health
JVM_FLAGS=()

# kloudmate-javaagent.jar: the extension embedded, configured through a file the agent arguments name.
run_kmagent() {
  local name="$1" agentarg="$2"; shift 2
  mkdir -p "$WORK/status-$name"
  env -i PATH="$PATH" HOME="$HOME" KM_SDK_STATUS_DIR="$WORK/status-$name" \
    OTEL_METRIC_EXPORT_INTERVAL=1000 OTEL_BSP_SCHEDULE_DELAY=200 "$@" \
    java -javaagent:"$agentarg" -cp "$WORK/app" App >"$WORK/app-$name.log" 2>&1 || { cat "$WORK/app-$name.log"; exit 1; }
}
# What the KloudMate Agent writes before it attaches: <name> <status dir> <service.name> <km.service_id>.
agent_props() {
  mkdir -p "$WORK/$2"
  printf '%s\n' "# written by the KloudMate Agent" "kloudmate.service_id=$4" "kloudmate.status_dir=$WORK/$2" \
    "otel.service.name=$3" "otel.resource.attributes=deployment.environment.name=attach" > "$WORK/$1.properties"
}

echo ">>> 7. kloudmate-javaagent.jar alone: the extension is embedded"
run_kmagent kmagent "$kmagent" KM_SERVICE_ID=systemd:smoke-kmagent.service OTEL_SERVICE_NAME=smoke-kmagent

echo ">>> 8. kloudmate-javaagent.jar=<file>: the file configures it, and the app's own settings still win"
agent_props agent status-attach smoke-attach-file proc:smoke-attach
run_kmagent kmagent-file "$kmagent=$WORK/agent.properties"
agent_props agent-app status-attach-app smoke-attach-file proc:smoke-attach
run_kmagent kmagent-file-app "$kmagent=$WORK/agent-app.properties" OTEL_SERVICE_NAME=smoke-attach-app

echo ">>> 9. runtime attach to a running JVM, configured by the file"
agent_props agent-runtime status-runtime smoke-runtime-attach proc:smoke-runtime
env -i PATH="$PATH" HOME="$HOME" OTEL_METRIC_EXPORT_INTERVAL=1000 OTEL_BSP_SCHEDULE_DELAY=200 \
  SMOKE_SLEEP_MS=12000 SMOKE_REPEAT_MS=300 java -cp "$WORK/app" App >"$WORK/app-runtime.log" 2>&1 &
app=$!
sleep 2
env -i PATH="$PATH" HOME="$HOME" java -cp "$WORK/attach" Attach "$app" "$kmagent" "$WORK/agent-runtime.properties" \
  >"$WORK/attach.log" 2>&1 || { cat "$WORK/attach.log"; kill "$app"; exit 1; }
wait "$app" || { cat "$WORK/app-runtime.log"; exit 1; }

sleep 1
python3 "$HERE/assert.py" "$WORK"
echo ">>> java smoke: PASS ($WORK)"
