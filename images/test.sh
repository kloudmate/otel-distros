#!/usr/bin/env bash
# End-to-end check of a locally loaded image (images/build.sh <lang> --load): the distribution is copied into a
# world-writable volume as an init container would, and also mounted as an image volume; the language's smoke app
# then runs in a stock runtime image (glibc and musl where they differ) with only the activation variables set,
# and its telemetry must reach a local OTLP sink as kloudmate-<lang>.
#   images/test.sh <java|python|nodejs|dotnet> [image]     (needs Docker 28+ for image mounts, Go, linux amd64)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
LANG_="${1:?usage: $0 <java|python|nodejs|dotnet> [image]}"
VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["distro"]["version"])' "$ROOT/$LANG_/km-manifest.json")"
IMAGE="${2:-ghcr.io/kloudmate/otel-distro-$LANG_:$VERSION}"
MIRROR="${IMAGE_MIRROR:-mirror.gcr.io/library}"
WORK="${SMOKE_WORK:-$(mktemp -d)}"
VOL="km-image-test-$LANG_-$$"
mkdir -p "$WORK"
PIDS=()
cleanup() {
  for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done
  docker volume rm -f "$VOL" >/dev/null 2>&1 || true
}
trap cleanup EXIT

(cd "$ROOT/smoke/otlpsink" && go build -o "$WORK/otlpsink" .)
"$WORK/otlpsink" -listen 127.0.0.1:4318 -out "$WORK/sink.jsonl" 2>"$WORK/sink.log" & PIDS+=($!)
sleep 1

# Init-container mode: an emptyDir is world-writable; the image runs as 65532 and copies itself in.
docker volume create "$VOL" >/dev/null
docker run --rm -v "$VOL:/otel" --entrypoint chmod "$IMAGE" 0777 /otel 2>/dev/null \
  || docker run --rm -u 0 -v "$VOL:/otel" --entrypoint chmod "$IMAGE" 0777 /otel
docker run --rm -v "$VOL:/otel" "$IMAGE" cp -r /autoinstrumentation/. /otel/

EXPECT=()
# run <name> <mode: copy|image> <runtime image> <home inside the app container> <command...>; ENVS holds the activation.
ENVS=()
run() {
  local name="$1" mode="$2" runtime="$3"; shift 3
  local mount=(-v "$VOL:/otel:ro")
  [ "$mode" = image ] && mount=(--mount "type=image,source=$IMAGE,target=/otel-image")
  local envs=(-e "OTEL_SERVICE_NAME=img-$name" -e OTEL_METRIC_EXPORT_INTERVAL=1000 -e OTEL_BSP_SCHEDULE_DELAY=200)
  for kv in "${ENVS[@]}"; do envs+=(-e "$kv"); done
  echo ">>> $name"
  docker run --rm --network host "${mount[@]}" -v "$WORK/app:/app:ro" "${envs[@]}" "$runtime" "$@" \
    >"$WORK/app-$name.log" 2>&1 || { cat "$WORK/app-$name.log"; exit 1; }
  EXPECT+=("img-$name")
}
home() { [ "$1" = image ] && echo /otel-image/autoinstrumentation || echo /otel; }

mkdir -p "$WORK/app"
case "$LANG_" in
  nodejs)
    cp "$ROOT/nodejs/smoke/app.cjs" "$WORK/app/"
    for mode in copy image; do
      ENVS=("NODE_OPTIONS=--require $(home $mode)/register.js")
      run "nodejs-$mode" "$mode" "$MIRROR/node:22-slim" node /app/app.cjs
    done
    ENVS=("NODE_OPTIONS=--require /otel/register.js")
    run nodejs-alpine copy "$MIRROR/node:22-alpine" node /app/app.cjs
    ;;
  python)
    cp "$ROOT/python/smoke/app.py" "$WORK/app/"
    for mode in copy image; do
      ENVS=("PYTHONPATH=$(home $mode)")
      run "python-$mode" "$mode" "$MIRROR/python:3.12-slim" python /app/app.py
    done
    ENVS=("PYTHONPATH=/otel")
    run python-alpine copy "$MIRROR/python:3.13-alpine" python /app/app.py
    run python-3.10 copy "$MIRROR/python:3.10-slim" python /app/app.py
    ;;
  java)
    javac --release 8 -d "$WORK/app" "$ROOT/java/smoke/App.java" 2>/dev/null
    for mode in copy image; do
      h="$(home $mode)"
      ENVS=("JAVA_TOOL_OPTIONS=-javaagent:$h/kloudmate-javaagent.jar")
      run "java-$mode" "$mode" "$MIRROR/eclipse-temurin:21-jre" java -cp /app App
    done
    ;;
  dotnet)
    [ -f "$ROOT/dotnet/build/smoke-app/SmokeApp.dll" ] \
      || "$ROOT/dotnet/dotnet.sh" publish smoke/app/SmokeApp.csproj -c Release -o build/smoke-app >/dev/null
    cp -r "$ROOT/dotnet/build/smoke-app/." "$WORK/app/"
    for variant in copy:linux-x64:8.0 image:linux-x64:8.0 copy:linux-musl-x64:8.0-alpine; do
      IFS=: read -r mode native tag <<<"$variant"
      h="$(home "$mode")"
      ENVS=("OTEL_DOTNET_AUTO_HOME=$h" "DOTNET_STARTUP_HOOKS=$h/net/OpenTelemetry.AutoInstrumentation.StartupHook.dll"
        "CORECLR_ENABLE_PROFILING=1" "CORECLR_PROFILER={918728DD-259F-4A6A-AC2B-B85E1B658318}"
        "CORECLR_PROFILER_PATH=$h/$native/OpenTelemetry.AutoInstrumentation.Native.so"
        "OTEL_DOTNET_AUTO_PLUGINS=KloudMate.OpenTelemetry.Plugin, KloudMate.OpenTelemetry")
      run "dotnet-$mode-$native" "$mode" "mcr.microsoft.com/dotnet/runtime:$tag" dotnet /app/SmokeApp.dll
    done
    ;;
  *) echo "unknown language $LANG_" >&2; exit 2 ;;
esac

sleep 1
python3 - "$WORK/sink.jsonl" "kloudmate-$LANG_" "${EXPECT[@]}" <<'PY'
import json, sys
path, distro, *names = sys.argv[1:]
seen = {}
for line in open(path):
    rec = json.loads(line)
    if rec["signal"] != "traces":
        continue
    for r in rec["resources"]:
        seen.setdefault(r.get("service.name"), r)
fails = 0
for n in names:
    r = seen.get(n)
    ok = r is not None and r.get("telemetry.distro.name") == distro
    fails += not ok
    print(("  ok    " if ok else "  FAIL  ") + f"{n}: traces as {distro}" + ("" if ok else f" (got {r and r.get('telemetry.distro.name')})"))
sys.exit(1 if fails else 0)
PY
echo ">>> $LANG_ image: PASS ($WORK)"
