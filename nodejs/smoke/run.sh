#!/usr/bin/env bash
# Local smoke test for the KloudMate Node.js distro. Lays out the package the way the KloudMate Agent ships it
# (register.js + node_modules from the lockfile), runs sample apps with only NODE_OPTIONS pointing at it, and
# asserts what reaches the OTLP sink and the status file.
#   nodejs/smoke/run.sh          (needs Node.js >=18.19 with npm, Go; Docker optional for the old-Node check)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="${SMOKE_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

bundle="$WORK/bundle"
mkdir -p "$bundle"
cp -r "$ROOT/nodejs/register.js" "$ROOT/nodejs/register.mjs" "$ROOT/nodejs/src" \
  "$ROOT/nodejs/package.json" "$ROOT/nodejs/package-lock.json" "$bundle/"
(cd "$bundle" && npm ci --omit=dev --ignore-scripts --no-audit --no-fund >/dev/null)

(cd "$ROOT/smoke/otlpsink" && go build -o "$WORK/otlpsink" .)
"$WORK/otlpsink" -listen 127.0.0.1:4318 -out "$WORK/default.jsonl" 2>"$WORK/sink-default.log" & PIDS+=($!)
"$WORK/otlpsink" -listen 127.0.0.1:14318 -out "$WORK/app.jsonl" 2>"$WORK/sink-app.log" & PIDS+=($!)
"$WORK/otlpsink" -listen 127.0.0.1:24318 -out "$WORK/file.jsonl" 2>"$WORK/sink-file.log" & PIDS+=($!)
"$WORK/otlpsink" -listen 127.0.0.1:34318 -out "$WORK/esm.jsonl" 2>"$WORK/sink-esm.log" & PIDS+=($!)
sleep 1

run_app() {
  local name="$1" opts="$2" app="$3"; shift 3
  mkdir -p "$WORK/status-$name"
  env -i PATH="$PATH" HOME="$HOME" NODE_OPTIONS="$opts" KM_SDK_STATUS_DIR="$WORK/status-$name" \
    OTEL_METRIC_EXPORT_INTERVAL=1000 OTEL_BSP_SCHEDULE_DELAY=200 "$@" \
    node "$app" >"$WORK/app-$name.log" 2>&1 || { cat "$WORK/app-$name.log"; exit 1; }
}
REQ="--require $bundle/register.js"

echo ">>> 1. CommonJS, KloudMate defaults"
run_app defaults "$REQ" "$HERE/app.cjs" KM_SERVICE_ID=systemd:smoke.service OTEL_SERVICE_NAME=smoke-defaults

echo ">>> 2. the app's own OTEL_* win"
run_app appenv "$REQ" "$HERE/app.cjs" KM_SERVICE_ID=systemd:smoke.service OTEL_SERVICE_NAME=smoke-app \
  OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:14318 \
  OTEL_RESOURCE_ATTRIBUTES=km.service_id=app-own,deployment.environment.name=prod \
  SMOKE_TARGET=http://127.0.0.1:14318/health

echo ">>> 3. ESM with --import"
run_app esm "--import $bundle/register.mjs" "$HERE/app.mjs" KM_SERVICE_ID=systemd:smoke-esm.service \
  OTEL_SERVICE_NAME=smoke-esm OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:34318 SMOKE_TARGET=http://127.0.0.1:34318/health

echo ">>> 4. config file (opt-in, experimental upstream)"
run_app file "$REQ" "$HERE/app.cjs" OTEL_CONFIG_FILE="$HERE/config.yaml" SMOKE_FILE_PORT=24318 \
  KM_SERVICE_ID=ignored-by-block SMOKE_TARGET=http://127.0.0.1:24318/health

echo ">>> 4b. a config file whose \${OTEL_SERVICE_NAME} the app supplies"
run_app file-app "$REQ" "$HERE/app.cjs" OTEL_CONFIG_FILE="$HERE/config.yaml" SMOKE_FILE_PORT=24318 \
  OTEL_SERVICE_NAME=smoke-file-app SMOKE_TARGET=http://127.0.0.1:24318/health

echo ">>> 5. SIGTERM still terminates an app without its own handler"
mkdir -p "$WORK/status-sigterm"
env -i PATH="$PATH" HOME="$HOME" NODE_OPTIONS="$REQ" KM_SDK_STATUS_DIR="$WORK/status-sigterm" \
  OTEL_SERVICE_NAME=smoke-sigterm OTEL_BSP_SCHEDULE_DELAY=60000 SMOKE_SLEEP_MS=60000 \
  node "$HERE/app.cjs" >"$WORK/app-sigterm.log" 2>&1 &
app=$!
sleep 2
kill -TERM "$app"
rc=0
for _ in $(seq 1 50); do kill -0 "$app" 2>/dev/null || break; sleep 0.1; done
if kill -0 "$app" 2>/dev/null; then kill -9 "$app"; echo "still running after SIGTERM" > "$WORK/sigterm.result"
else wait "$app" || rc=$?; echo "exit=$rc" > "$WORK/sigterm.result"; fi

if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
  echo ">>> 6. Node.js 16: the app runs, the status says why"
  mkdir -p "$WORK/status-node16"
  docker run --rm -v "$bundle:/b:ro" -v "$WORK/status-node16:/st" -e "NODE_OPTIONS=--require /b/register.js" \
    -e KM_SDK_STATUS_DIR=/st "${NODE16_IMAGE:-mirror.gcr.io/library/node:16-slim}" node -e 'console.log("app ran")' \
    > "$WORK/app-node16.log" 2>&1 || { cat "$WORK/app-node16.log"; exit 1; }
fi

sleep 1
python3 "$HERE/assert.py" "$WORK"
echo ">>> nodejs smoke: PASS ($WORK)"
