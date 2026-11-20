#!/usr/bin/env bash
# Builds the Node.js distro release into <out> (default nodejs/build/release): the npm package tarball, the
# package-lock.json that pins its whole dependency tree (install with `npm ci --omit=dev`), a km-manifest.json
# with real sha256s and provenance, and SHA256SUMS.
#   nodejs/release.sh [out]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/build/release}"
rm -rf "$OUT"; mkdir -p "$OUT"
(cd "$HERE" && npm ci --ignore-scripts --no-audit --no-fund >/dev/null && npm test >/dev/null)
(cd "$HERE" && npm pack --pack-destination "$OUT" >/dev/null)   # npm pack is reproducible (fixed mtimes)
cp "$HERE/package-lock.json" "$OUT/"
COMMIT="$(git -C "$HERE" rev-parse -q --verify HEAD 2>/dev/null || echo 0000000000000000000000000000000000000000)"
node - "$HERE/km-manifest.json" "$OUT" "$COMMIT" <<'JS'
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const [src, out, commit] = process.argv.slice(2);
const m = JSON.parse(fs.readFileSync(src, 'utf8'));
for (const a of m.artifacts) {
  const p = path.join(out, a.path);
  if (!fs.existsSync(p)) { console.error(`missing artifact ${a.path} (versions in km-manifest.json and package.json must match)`); process.exit(1); }
  a.sha256 = crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
}
m.provenance.commit = commit;
m.provenance.built_at = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
fs.writeFileSync(path.join(out, 'km-manifest.json'), JSON.stringify(m, null, 2) + '\n');
JS
(cd "$OUT" && sha256sum ./*.tgz package-lock.json km-manifest.json | sed 's| \./| |' > SHA256SUMS)
echo "kloudmate-nodejs -> $OUT"; cat "$OUT/SHA256SUMS"
