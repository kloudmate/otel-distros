#!/usr/bin/env bash
# Builds the Kubernetes image for one language: the distribution laid out under /autoinstrumentation for amd64 and
# arm64, on a pinned busybox (images/Dockerfile). Without --push nothing leaves the machine: the multi-arch image is
# written as an OCI archive, and --load puts this machine's architecture into the local Docker instead.
#   images/build.sh <java|python|nodejs|dotnet> [--tag <ref>] [--load | --push] [--out <dir>]
# Needs Docker with buildx, curl, unzip; Python needs CPython 3 with pip, Node.js needs npm, Java needs a JDK and
# Gradle, .NET needs Docker (the SDK runs in a container).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
LANG_="${1:-}"; shift || true
case "$LANG_" in java|python|nodejs|dotnet) ;; *) echo "usage: $0 <java|python|nodejs|dotnet> [--tag ref] [--load|--push] [--out dir]" >&2; exit 2 ;; esac
VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["distro"]["version"])' "$ROOT/$LANG_/km-manifest.json")"
TAG="ghcr.io/kloudmate/otel-distro-$LANG_:$VERSION"
MODE=oci
OUT="$ROOT/images/build"
while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="$2"; shift 2 ;;
    --load) MODE=load; shift ;;
    --push) MODE=push; shift ;;
    --out) OUT="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
CACHE="${SMOKE_CACHE:-$HOME/.cache/kloudmate-otel-distros}"
CTX="$OUT/$LANG_/context"
REL="$OUT/$LANG_/release"
rm -rf "$OUT/$LANG_"; mkdir -p "$CTX/amd64" "$CTX/arm64" "$CACHE"

fetch() { # url sha256 dest: download once into the cache, verify every time
  local url="$1" sum="$2" dest="$3"
  if [ ! -f "$dest" ]; then curl -fsSL --retry 3 -o "$dest.tmp" "$url" && mv "$dest.tmp" "$dest"; fi
  echo "$sum  $dest" | sha256sum -c --quiet -
}

# kloudmate-javaagent.jar: the upstream agent (pinned by sha256 in the build) with the extension embedded.
layout_java() {
  "$ROOT/java/release.sh" "$REL" >/dev/null
  for arch in amd64 arm64; do
    cp "$REL/kloudmate-javaagent.jar" "$REL/km-manifest.json" "$CTX/$arch/"
  done
}

layout_nodejs() {
  "$ROOT/nodejs/release.sh" "$REL" >/dev/null
  local d="$CTX/amd64"
  tar -xzf "$REL"/*.tgz -C "$d" --strip-components=1 package
  cp "$REL/package-lock.json" "$d/"
  (cd "$d" && npm ci --omit=dev --ignore-scripts --no-audit --no-fund >/dev/null)
  if find "$d/node_modules" -name '*.node' | grep -q .; then echo "native Node.js addons found: one layout no longer fits both architectures" >&2; exit 1; fi
  cp "$REL/km-manifest.json" "$d/"
  cp -a "$d/." "$CTX/arm64/"
}

# One cell per CPython minor and libc: <minor>/ for glibc (manylinux 2.17), <minor>-musl/ for musl (musllinux 1.2,
# so Alpine 3.13 and later).
layout_python() {
  "$ROOT/python/release.sh" "$REL" >/dev/null
  local venv="$OUT/python/venv" whl minors arch march minor abi
  whl="$(ls "$REL"/*.whl)"
  minors="$(python3 - "$ROOT/python/km-manifest.json" <<'PY'
import json, re, sys
rng = json.load(open(sys.argv[1]))["runtime"]["version_range"]
lo = re.search(r">=\s*3\.(\d+)", rng).group(1); hi = re.search(r"<\s*3\.(\d+)", rng).group(1)
print(" ".join(f"3.{m}" for m in range(int(lo), int(hi))))
PY
)"
  python3 -m venv "$venv" && "$venv/bin/pip" -q install --upgrade pip
  for arch in amd64 arm64; do
    march=x86_64; [ "$arch" = arm64 ] && march=aarch64
    for minor in $minors; do
      abi="cp${minor/./}"
      "$venv/bin/pip" -q install --target "$CTX/$arch/$minor" --only-binary=:all: --no-compile \
        --python-version "$minor" --implementation cp --abi "$abi" --abi abi3 --abi none \
        --platform "manylinux_2_17_$march" --platform "manylinux2014_$march" \
        "$whl" -r "$HERE/python-instrumentations.txt"
      "$venv/bin/pip" -q install --target "$CTX/$arch/$minor-musl" --only-binary=:all: --no-compile \
        --python-version "$minor" --implementation cp --abi "$abi" --abi abi3 --abi none \
        --platform "musllinux_1_1_$march" --platform "musllinux_1_2_$march" \
        "$whl" -r "$HERE/python-instrumentations.txt"
    done
    rm -rf "$CTX/$arch"/*/bin
    cp "$REL/sitecustomize.py" "$REL/km-manifest.json" "$CTX/$arch/"
    echo "$VERSION" > "$CTX/$arch/VERSION"
  done
}

# Upstream's automatic instrumentation for glibc and musl (their native profilers sit side by side), and the plugin.
layout_dotnet() {
  "$ROOT/dotnet/release.sh" "$REL" >/dev/null
  local v base sums arch zarch libc z
  v="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["distro"]["upstream"]["version"])' "$REL/km-manifest.json")"
  base="https://github.com/open-telemetry/opentelemetry-dotnet-instrumentation/releases/download/v$v"
  sums="$CACHE/checksums-$v.txt"
  [ -f "$sums" ] || curl -fsSL --retry 3 -o "$sums" "$base/checksums.txt"
  for arch in amd64 arm64; do
    zarch=x64; [ "$arch" = arm64 ] && zarch=arm64
    for libc in glibc musl; do
      z="opentelemetry-dotnet-instrumentation-linux-$libc-$zarch.zip"
      fetch "$base/$z" "$(grep " $z\$" "$sums" | cut -d' ' -f1)" "$CACHE/$v-$z"
      mkdir -p "$OUT/dotnet/$arch-$libc" && (cd "$OUT/dotnet/$arch-$libc" && unzip -q "$CACHE/$v-$z")
    done
    # Both zips carry the same managed files; only the native profiler directories differ, and VERSION, whose build
    # annotation does (the glibc one, copied last, names the release tag).
    python3 - "$OUT/dotnet/$arch-glibc" "$OUT/dotnet/$arch-musl" <<'PY'
import filecmp, os, sys
a, b = sys.argv[1:3]
for d, _, files in os.walk(b):
    for f in files:
        p = os.path.join(a, os.path.relpath(os.path.join(d, f), b))
        if os.path.relpath(p, a) != "VERSION" and os.path.exists(p) and not filecmp.cmp(p, os.path.join(d, f), shallow=False):
            sys.exit(f"glibc and musl differ at {os.path.relpath(p, a)}")
PY
    cp -a "$OUT/dotnet/$arch-musl/." "$OUT/dotnet/$arch-glibc/." "$CTX/$arch/"
    cp "$REL/KloudMate.OpenTelemetry.dll" "$CTX/$arch/net/"
    cp "$REL/km-manifest.json" "$CTX/$arch/"
  done
}

"layout_$LANG_"

base=()
[ -n "${BASE_IMAGE:-}" ] && base=(--build-arg "BASE=$BASE_IMAGE")
labels=(--label "org.opencontainers.image.source=https://github.com/kloudmate/otel-distros"
  --label "org.opencontainers.image.version=$VERSION"
  --label "org.opencontainers.image.revision=$(git -C "$ROOT" rev-parse -q --verify HEAD 2>/dev/null || echo unknown)"
  --label "org.opencontainers.image.licenses=Apache-2.0")
case "$MODE" in
  load) docker buildx build -q "${base[@]}" "${labels[@]}" -f "$HERE/Dockerfile" -t "$TAG" --load "$CTX" >/dev/null ;;
  push) docker buildx build -q "${base[@]}" "${labels[@]}" -f "$HERE/Dockerfile" -t "$TAG" \
          --platform linux/amd64,linux/arm64 --push "$CTX" ;;
  oci)  docker buildx build -q "${base[@]}" "${labels[@]}" -f "$HERE/Dockerfile" -t "$TAG" \
          --platform linux/amd64,linux/arm64 --output "type=oci,dest=$OUT/$LANG_.oci.tar" "$CTX" >/dev/null
        echo "$TAG -> $OUT/$LANG_.oci.tar" ;;
esac
du -sh "$CTX/amd64" "$CTX/arm64" | sed 's|^|  |'
echo "$TAG ($MODE)"
