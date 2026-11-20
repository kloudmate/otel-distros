#!/usr/bin/env bash
# Runs the .NET SDK in Docker (no local SDK needed): dotnet.sh <dotnet args...>, from the repository's dotnet/ dir.
# Behind an HTTPS proxy, the proxy and its CA bundle (SSL_CERT_FILE) are passed through.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE="${DOTNET_SDK_IMAGE:-mcr.microsoft.com/dotnet/sdk:8.0}"
# Runs as root (the SDK's first-run setup fails for some non-root uids) and hands what it wrote back to the
# caller afterwards, as scripts/otel-agent-bundles/build.sh in km-agent does.
args=(--rm -v "$HERE:/src" -w /src -e HOME=/src/.home -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)"
  -e DOTNET_CLI_TELEMETRY_OPTOUT=1 -e DOTNET_NOLOGO=1 -e DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1)
if [ -n "${HTTPS_PROXY:-}" ]; then
  args+=(--network host -e "HTTPS_PROXY=$HTTPS_PROXY" -e "https_proxy=$HTTPS_PROXY")
  ca="${SSL_CERT_FILE:-}"
  [ -z "$ca" ] && [ -f /root/.ccr/ca-bundle.crt ] && ca=/root/.ccr/ca-bundle.crt
  [ -n "$ca" ] && args+=(-v "$ca:/etc/ssl/certs/proxy-ca-bundle.crt:ro" -e SSL_CERT_FILE=/etc/ssl/certs/proxy-ca-bundle.crt)
fi
exec docker run "${args[@]}" "$IMAGE" sh -c 'dotnet "$@"; rc=$?; chown -R "$HOST_UID:$HOST_GID" /src; exit $rc' sh "$@"
