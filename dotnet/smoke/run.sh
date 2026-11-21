#!/usr/bin/env bash
# Local smoke test for the KloudMate .NET distro: builds the plugin and a sample app with the .NET SDK in Docker,
# lays out the upstream automatic instrumentation 1.19.1 with the plugin next to it, runs the app the way
# instrument.sh would, and asserts what reaches the OTLP sink and the status file.
#   dotnet/smoke/run.sh          (needs Docker, Go, curl; linux x64)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DN="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$DN/.." && pwd)"
VERSION="${DOTNET_AUTO_VERSION:-1.19.1}"
RUNTIME_IMAGE="${DOTNET_RUNTIME_IMAGE:-mcr.microsoft.com/dotnet/runtime:8.0}"
WORK="${SMOKE_WORK:-$(mktemp -d)}"
CACHE="${SMOKE_CACHE:-$HOME/.cache/kloudmate-otel-distros}"
mkdir -p "$WORK" "$CACHE"
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

zip="$CACHE/opentelemetry-dotnet-instrumentation-$VERSION-linux-glibc-x64.zip"
if [ ! -f "$zip" ]; then
  base="https://github.com/open-telemetry/opentelemetry-dotnet-instrumentation/releases/download/v$VERSION"
  curl -fsSL --retry 3 -o "$zip.tmp" "$base/opentelemetry-dotnet-instrumentation-linux-glibc-x64.zip"
  curl -fsSL --retry 3 -o "$CACHE/checksums-$VERSION.txt" "$base/checksums.txt"
  want="$(grep " opentelemetry-dotnet-instrumentation-linux-glibc-x64.zip$" "$CACHE/checksums-$VERSION.txt" | cut -d' ' -f1)"
  echo "$want  $zip.tmp" | sha256sum -c --quiet -
  mv "$zip.tmp" "$zip"
fi
home="$WORK/otel"
mkdir -p "$home" && (cd "$home" && unzip -q "$zip")

"$DN/dotnet.sh" build -c Release -o build/out >/dev/null
"$DN/dotnet.sh" publish smoke/app/SmokeApp.csproj -c Release -o build/smoke-app >/dev/null
cp "$DN/build/out/KloudMate.OpenTelemetry.dll" "$home/net/"

(cd "$ROOT/smoke/otlpsink" && go build -o "$WORK/otlpsink" .)
"$WORK/otlpsink" -listen 127.0.0.1:4318 -out "$WORK/default.jsonl" 2>"$WORK/sink-default.log" & PIDS+=($!)
"$WORK/otlpsink" -listen 127.0.0.1:14318 -out "$WORK/app.jsonl" 2>"$WORK/sink-app.log" & PIDS+=($!)
"$WORK/otlpsink" -listen 127.0.0.1:24318 -out "$WORK/file.jsonl" 2>"$WORK/sink-file.log" & PIDS+=($!)
"$WORK/otlpsink" -listen 127.0.0.1:34318 -out "$WORK/upstream.jsonl" 2>"$WORK/sink-upstream.log" & PIDS+=($!)
# The KloudMate Agent's socket speaks OTLP/gRPC; containers mount its directory.
mkdir -p "$WORK/km"
"$WORK/otlpsink" -listen "grpc+unix:$WORK/km/otlp.sock" -out "$WORK/socket.jsonl" 2>"$WORK/sink-socket.log" & PIDS+=($!)
sleep 1

# The activation instrument.sh performs, plus the plugin; host network so 127.0.0.1 is the sink.
run_app() {
  local name="$1" otelhome="$2"; shift 2
  mkdir -p "$WORK/status-$name"
  local envs=(-e OTEL_DOTNET_AUTO_HOME=/otel
    -e DOTNET_STARTUP_HOOKS=/otel/net/OpenTelemetry.AutoInstrumentation.StartupHook.dll
    -e CORECLR_ENABLE_PROFILING=1 -e "CORECLR_PROFILER={918728DD-259F-4A6A-AC2B-B85E1B658318}"
    -e CORECLR_PROFILER_PATH=/otel/linux-x64/OpenTelemetry.AutoInstrumentation.Native.so
    -e "OTEL_DOTNET_AUTO_PLUGINS=KloudMate.OpenTelemetry.Plugin, KloudMate.OpenTelemetry"
    -e KM_SDK_STATUS_DIR=/st -e OTEL_METRIC_EXPORT_INTERVAL=1000 -e OTEL_BSP_SCHEDULE_DELAY=200)
  for kv in "$@"; do envs+=(-e "$kv"); done
  docker run --rm --network host --user "$(id -u):$(id -g)" -v "$otelhome:/otel:ro" -v "$DN/build/smoke-app:/app:ro" \
    -v "$HERE/config.yaml:/cfg/config.yaml:ro" -v "$HERE/config-socket.yaml:/cfg/config-socket.yaml:ro" -v "$WORK/km:/km" \
    -v "$WORK/status-$name:/st" "${envs[@]}" "$RUNTIME_IMAGE" dotnet /app/SmokeApp.dll >"$WORK/app-$name.log" 2>&1 \
    || { cat "$WORK/app-$name.log"; exit 1; }
}

echo ">>> 1. KloudMate defaults"
run_app defaults "$home" KM_SERVICE_ID=systemd:smoke.service OTEL_SERVICE_NAME=smoke-defaults

echo ">>> 2. the app's own OTEL_* win"
run_app appenv "$home" KM_SERVICE_ID=systemd:smoke.service OTEL_SERVICE_NAME=smoke-app \
  OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:14318 \
  OTEL_RESOURCE_ATTRIBUTES=km.service_id=app-own,deployment.environment.name=prod \
  SMOKE_TARGET=http://127.0.0.1:14318/health

echo ">>> 3. another vendor's agent configured: status degraded"
run_app foreign "$home" OTEL_SERVICE_NAME=smoke-foreign DD_TRACE_ENABLED=false SMOKE_SLEEP_MS=500

echo ">>> 4. plugin missing: the app still runs on upstream defaults"
nohome="$WORK/otel-noplugin"; cp -r "$home" "$nohome"; rm "$nohome/net/KloudMate.OpenTelemetry.dll"
run_app noplugin "$nohome" OTEL_SERVICE_NAME=smoke-noplugin SMOKE_SLEEP_MS=500

echo ">>> 5. a config file, upstream's file mode off: the plugin reads distribution.kloudmate"
run_app file-env "$home" OTEL_CONFIG_FILE=/cfg/config.yaml SMOKE_FILE_PORT=24318 KM_SERVICE_ID=ignored-by-block \
  OTEL_SERVICE_NAME=smoke-file-env SMOKE_TARGET=http://127.0.0.1:24318/health

echo ">>> 6. a config file in upstream's file mode, the plugin listed in it"
run_app file-upstream "$home" OTEL_CONFIG_FILE=/cfg/config.yaml SMOKE_FILE_PORT=34318 \
  OTEL_EXPERIMENTAL_FILE_BASED_CONFIGURATION_ENABLED=true OTEL_DOTNET_AUTO_PLUGINS= SMOKE_TARGET=http://127.0.0.1:34318/health

echo ">>> 7. the agent's socket (OTLP/gRPC)"
run_app socket "$home" KM_SERVICE_ID=systemd:smoke-socket.service OTEL_SERVICE_NAME=smoke-socket KM_OTLP_SOCKET=/km/otlp.sock

echo ">>> 7b. a socket path with nothing listening: TCP"
run_app nosocket "$home" KM_SERVICE_ID=systemd:smoke-nosocket.service OTEL_SERVICE_NAME=smoke-nosocket \
  KM_OTLP_SOCKET=/km/missing.sock

echo ">>> 7c. upstream's file mode, otlp_grpc exporters without an endpoint: the socket"
run_app file-socket "$home" OTEL_CONFIG_FILE=/cfg/config-socket.yaml \
  OTEL_EXPERIMENTAL_FILE_BASED_CONFIGURATION_ENABLED=true OTEL_DOTNET_AUTO_PLUGINS= SMOKE_TARGET=http://127.0.0.1:4318/health

sleep 1
python3 "$HERE/assert.py" "$WORK"
echo ">>> dotnet smoke: PASS ($WORK)"
