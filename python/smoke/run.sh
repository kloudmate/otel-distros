#!/usr/bin/env bash
# Local smoke test for the KloudMate Python distro. Builds the wheel, lays out a bundle the way km-agent
# ships it (sitecustomize.py + a cell for this interpreter's minor), runs a sample app with only
# PYTHONPATH pointing at it, and asserts what reaches the OTLP sink and the status file.
#   python/smoke/run.sh            (needs CPython >=3.10 with venv, Go; Docker optional for the 2.7 check)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PY="${PYTHON:-python3}"
WORK="${SMOKE_WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

"$PY" -m venv "$WORK/venv"
"$WORK/venv/bin/pip" -q install build
"$WORK/venv/bin/python" -m build -q --wheel --outdir "$WORK/dist" "$ROOT/python" >/dev/null
minor="$("$PY" -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
bundle="$WORK/bundle"
mkdir -p "$bundle/$minor"
"$WORK/venv/bin/pip" -q install --target "$bundle/$minor" "$WORK"/dist/*.whl \
  opentelemetry-instrumentation-urllib==0.66b2
cp "$ROOT/python/bootstrap/sitecustomize.py" "$bundle/"
"$PY" -c 'import tomllib,sys; print(tomllib.load(open(sys.argv[1],"rb"))["project"]["version"])' \
  "$ROOT/python/pyproject.toml" > "$bundle/VERSION"

(cd "$ROOT/smoke/otlpsink" && go build -o "$WORK/otlpsink" .)
"$WORK/otlpsink" -listen 127.0.0.1:4318 -out "$WORK/default.jsonl" 2>"$WORK/sink-default.log" & PIDS+=($!)
"$WORK/otlpsink" -listen 127.0.0.1:14318 -out "$WORK/app.jsonl" 2>"$WORK/sink-app.log" & PIDS+=($!)
"$WORK/otlpsink" -listen 127.0.0.1:24318 -out "$WORK/file.jsonl" 2>"$WORK/sink-file.log" & PIDS+=($!)
sleep 1

# Only what a scenario sets, as km-agent's activation would: PYTHONPATH and the KM_/OTEL_ vars.
run_app() {
  local name="$1"; shift
  mkdir -p "$WORK/status-$name"
  env -i PATH="$PATH" HOME="$HOME" PYTHONPATH="$bundle" KM_SDK_STATUS_DIR="$WORK/status-$name" \
    OTEL_METRIC_EXPORT_INTERVAL=1000 OTEL_BSP_SCHEDULE_DELAY=200 OTEL_BLRP_SCHEDULE_DELAY=200 "$@" \
    "$PY" "$HERE/app.py" >"$WORK/app-$name.log" 2>&1 || { cat "$WORK/app-$name.log"; exit 1; }
}

echo ">>> 1. env mode, KloudMate defaults"
run_app defaults KM_SERVICE_ID=systemd:smoke.service OTEL_SERVICE_NAME=smoke-defaults

echo ">>> 2. env mode, the app's own OTEL_* win"
run_app appenv KM_SERVICE_ID=systemd:smoke.service OTEL_SERVICE_NAME=smoke-app \
  OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:14318 \
  OTEL_RESOURCE_ATTRIBUTES=km.service_id=app-own,deployment.environment.name=prod \
  SMOKE_TARGET=http://127.0.0.1:14318/health

echo ">>> 3. declarative config file with distribution.kloudmate"
run_app file OTEL_CONFIG_FILE="$HERE/config.yaml" SMOKE_FILE_PORT=24318 KM_SERVICE_ID=ignored-by-block \
  SMOKE_TARGET=http://127.0.0.1:24318/health

echo ">>> 3b. a config file whose \${OTEL_SERVICE_NAME} the app supplies"
run_app file-app OTEL_CONFIG_FILE="$HERE/config.yaml" SMOKE_FILE_PORT=24318 OTEL_SERVICE_NAME=smoke-file-app \
  SMOKE_TARGET=http://127.0.0.1:24318/health

echo ">>> 4. broken config file: the app still runs"
printf 'file_format: "1.0"\ntracer_provider: [not, a, map]\n' > "$WORK/broken.yaml"
run_app broken OTEL_CONFIG_FILE="$WORK/broken.yaml" SMOKE_SLEEP_S=0

if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
  echo ">>> 5. Python 2.7: bootstrap parses, app runs, status says why"
  mkdir -p "$WORK/status-py27"
  docker run --rm -v "$bundle:/b:ro" -v "$WORK/status-py27:/st" -e PYTHONPATH=/b -e KM_SDK_STATUS_DIR=/st \
    "${PY27_IMAGE:-mirror.gcr.io/library/python:2.7-slim}" python -c 'print("app ran")' > "$WORK/app-py27.log" 2>&1 \
    || { cat "$WORK/app-py27.log"; exit 1; }
fi

sleep 1
"$PY" "$HERE/assert.py" "$WORK"
echo ">>> python smoke: PASS ($WORK)"
