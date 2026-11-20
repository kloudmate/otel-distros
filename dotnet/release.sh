#!/usr/bin/env bash
# Builds the .NET distro release into <out> (default dotnet/build/release): the plugin assembly, a km-manifest.json
# with real sha256s and provenance, and SHA256SUMS. The manifest's artifact path is where the assembly is installed
# inside the upstream instrumentation home (net/); the release files themselves are flat, as release assets are.
# Uses the .NET SDK in Docker (dotnet.sh).
#   dotnet/release.sh [out]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/build/release}"
VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["distro"]["version"])' "$HERE/km-manifest.json")"
rm -rf "$OUT" "$HERE/build/out"; mkdir -p "$OUT"
"$HERE/dotnet.sh" build -c Release -o build/out -p:Version="$VERSION" >/dev/null
cp "$HERE/build/out/KloudMate.OpenTelemetry.dll" "$OUT/"
COMMIT="$(git -C "$HERE" rev-parse -q --verify HEAD 2>/dev/null || echo 0000000000000000000000000000000000000000)"
python3 - "$HERE/km-manifest.json" "$OUT" "$COMMIT" <<'PY'
import datetime, hashlib, json, os, sys
src, out, commit = sys.argv[1:4]
m = json.load(open(src))
for a in m["artifacts"]:
    a["sha256"] = hashlib.sha256(open(os.path.join(out, os.path.basename(a["path"])), "rb").read()).hexdigest()
m["provenance"]["commit"] = commit
m["provenance"]["built_at"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
json.dump(m, open(os.path.join(out, "km-manifest.json"), "w"), indent=2)
PY
(cd "$OUT" && sha256sum KloudMate.OpenTelemetry.dll km-manifest.json > SHA256SUMS)
echo "kloudmate-dotnet $VERSION -> $OUT"; cat "$OUT/SHA256SUMS"
