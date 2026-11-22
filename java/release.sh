#!/usr/bin/env bash
# Builds the Java distro release into <out> (default java/build/release): the extension jar, kloudmate-javaagent.jar
# (the upstream agent with the extension embedded, for runtime attach), a
# km-manifest.json with real sha256s and provenance, and SHA256SUMS. km-agent pins the jar's sha256.
#   java/release.sh [out]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/build/release}"
VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["distro"]["version"])' "$HERE/km-manifest.json")"
(cd "$HERE" && ./gradlew -q --no-daemon -PdistroVersion="$VERSION" clean jar agentJar test)
mkdir -p "$OUT"
cp "$HERE/build/libs/kloudmate-otel-extension.jar" "$HERE/build/libs/kloudmate-javaagent.jar" "$OUT/"
COMMIT="$(git -C "$HERE" rev-parse -q --verify HEAD 2>/dev/null || echo 0000000000000000000000000000000000000000)"
python3 - "$HERE/km-manifest.json" "$OUT" "$COMMIT" <<'PY'
import datetime, hashlib, json, sys
src, out, commit = sys.argv[1:4]
m = json.load(open(src))
for a in m["artifacts"]:
    if a["path"] in ("kloudmate-otel-extension.jar", "kloudmate-javaagent.jar"):
        a["sha256"] = hashlib.sha256(open(f"{out}/{a['path']}", "rb").read()).hexdigest()
m["provenance"]["commit"] = commit
m["provenance"]["built_at"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
json.dump(m, open(f"{out}/km-manifest.json", "w"), indent=2)
PY
(cd "$OUT" && sha256sum kloudmate-otel-extension.jar kloudmate-javaagent.jar km-manifest.json > SHA256SUMS)
echo "kloudmate-java $VERSION -> $OUT"
