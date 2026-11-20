#!/usr/bin/env bash
# Builds the Python distro release into <out> (default python/build/release): the wheel, the
# sitecustomize.py bootstrap, a km-manifest.json with real sha256s and provenance, and SHA256SUMS.
#   python/release.sh [out]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/build/release}"
PY="${PYTHON:-python3}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
"$PY" -m venv "$WORK/venv"
"$WORK/venv/bin/pip" -q install build
# Fixed timestamps so the wheel is byte-for-byte reproducible.
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-315532800}" \
  "$WORK/venv/bin/python" -m build -q --wheel --outdir "$OUT" "$HERE" >/dev/null
rm -rf "$HERE/build/lib" "$HERE"/src/*.egg-info
VERSION="$("$PY" -c 'import json,sys; print(json.load(open(sys.argv[1]))["distro"]["version"])' "$HERE/km-manifest.json")"
sed "s/^DISTRO_VERSION = \"0.0.0-dev\"/DISTRO_VERSION = \"$VERSION\"/" "$HERE/bootstrap/sitecustomize.py" > "$OUT/sitecustomize.py"
grep -q "^DISTRO_VERSION = \"$VERSION\"" "$OUT/sitecustomize.py" || { echo "version not stamped into sitecustomize.py" >&2; exit 1; }
COMMIT="$(git -C "$HERE" rev-parse -q --verify HEAD 2>/dev/null || echo 0000000000000000000000000000000000000000)"
"$PY" - "$HERE/km-manifest.json" "$OUT" "$COMMIT" <<'PY'
import datetime, hashlib, json, os, sys
src, out, commit = sys.argv[1:4]
m = json.load(open(src))
for a in m["artifacts"]:
    p = os.path.join(out, a["path"])
    if not os.path.exists(p):
        sys.exit(f"missing artifact {a['path']} (version in km-manifest.json and pyproject.toml must match)")
    a["sha256"] = hashlib.sha256(open(p, "rb").read()).hexdigest()
m["provenance"]["commit"] = commit
m["provenance"]["built_at"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
json.dump(m, open(os.path.join(out, "km-manifest.json"), "w"), indent=2)
PY
(cd "$OUT" && sha256sum ./*.whl sitecustomize.py km-manifest.json | sed 's| \./| |' > SHA256SUMS)
echo "kloudmate-python -> $OUT"; cat "$OUT/SHA256SUMS"
