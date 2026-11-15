#!/usr/bin/env bash
# Expose the Resprite AI tool API to the internet through Cloudflare Tunnel.
#
# Why a tunnel instead of LAN/"bind 0.0.0.0":
#   - the app keeps listening on 127.0.0.1 only, so nothing on your network can
#     reach it directly;
#   - Cloudflare terminates HTTPS at the edge, so the bearer token is never sent
#     in cleartext (LAN mode has no TLS);
#   - you get a URL that works from any network, and you can kill it instantly by
#     stopping this script.
#
# Usage (Termux / any Linux on the device):
#   ./tools/cf-tunnel.sh                 # quick tunnel, random *.trycloudflare.com URL
#   CLOUDFLARE_TUNNEL_TOKEN=... ./tools/cf-tunnel.sh
#                                        # named tunnel from the Cloudflare dashboard
#   PORT=47613 ./tools/cf-tunnel.sh      # override the local API port
#
# Prerequisite: start the API first (editor -> gear -> "AI access" -> Start server).
set -euo pipefail

PORT="${PORT:-47613}"
UPSTREAM="http://127.0.0.1:${PORT}"
BIN="${CLOUDFLARED:-$(command -v cloudflared || true)}"

if [[ -z "${BIN}" || ! -x "${BIN}" ]]; then
  BIN="$(dirname "$0")/cloudflared"
fi

if [[ ! -x "${BIN}" ]]; then
  echo "cloudflared not found. Install it, or drop the binary next to this script:"
  echo "  curl -L -o cloudflared https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
  echo "  chmod +x cloudflared"
  exit 1
fi

if ! curl -s -m 3 "${UPSTREAM}/health" >/dev/null 2>&1; then
  echo "WARNING: ${UPSTREAM}/health is not answering."
  echo "         Start the API in the app first (gear -> AI access -> Start server)."
  echo
fi

echo "Tunnelling ${UPSTREAM} ..."
if [[ -n "${CLOUDFLARE_TUNNEL_TOKEN:-}" ]]; then
  # Named tunnel: hostname + access policy come from the Cloudflare dashboard.
  exec "${BIN}" tunnel --no-autoupdate run --token "${CLOUDFLARE_TUNNEL_TOKEN}"
else
  # Quick tunnel: no Cloudflare account needed; URL is random and dies with this process.
  LOG="$(mktemp)"
  "${BIN}" tunnel --no-autoupdate --url "${UPSTREAM}" 2>&1 | tee "${LOG}" &
  TUNNEL_PID=$!
  trap 'kill "${TUNNEL_PID}" 2>/dev/null || true' EXIT

  for _ in $(seq 1 30); do
    URL="$(grep -ohE 'https://[a-z0-9-]+\.trycloudflare\.com' "${LOG}" 2>/dev/null | head -1 || true)"
    [[ -n "${URL}" ]] && break
    sleep 1
  done

  if [[ -z "${URL:-}" ]]; then
    echo "Could not read the tunnel URL; see the log above."
    wait "${TUNNEL_PID}"
    exit 1
  fi

  cat <<EOF

  Public endpoint : ${URL}/call
  Health check    : ${URL}/health
  Bearer token    : shown in the app (gear -> AI access)

  Try it:
    curl -s ${URL}/health
    curl -s -X POST -H "Authorization: Bearer <token>" \\
         -H "Content-Type: application/json" \\
         -d '{"tool":"ping","args":{}}' ${URL}/call

  Ctrl-C stops the tunnel.
EOF
  wait "${TUNNEL_PID}"
fi