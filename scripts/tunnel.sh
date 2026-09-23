#!/usr/bin/env bash
# Expose the local stack on a public HTTPS URL via a Cloudflare quick tunnel.
#
#   ./scripts/tunnel.sh
#
# No Cloudflare account and no domain needed. The URL is random and changes every restart,
# which is fine for a demo but means you cannot hard-code it in the mobile app -- put it in
# a config value the app reads at startup.
#
# Requires the stack to be up:  docker compose up -d
set -euo pipefail

cd "$(dirname "$0")/.."
PORT="${PORT:-8080}"
LOG="${LOG:-/tmp/techies-tunnel.log}"

command -v cloudflared >/dev/null || { echo "cloudflared not installed: brew install cloudflared" >&2; exit 1; }

if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${PORT}/api/categories" || true)" != "200" ]; then
  echo "The stack is not answering on :${PORT}. Start it first:" >&2
  echo "  docker compose up -d" >&2
  exit 1
fi

pkill -f "cloudflared tunnel" 2>/dev/null || true
sleep 1
: > "$LOG"
nohup cloudflared tunnel --url "http://localhost:${PORT}" --no-autoupdate > "$LOG" 2>&1 &

for _ in $(seq 1 40); do
  URL=$(grep -oE "https://[a-z0-9-]+\.trycloudflare\.com" "$LOG" 2>/dev/null | head -1 || true)
  if [ -n "${URL:-}" ]; then
    echo "$URL" > /tmp/techies-tunnel-url
    echo
    echo "  Public API:  ${URL}/api"
    echo "  Try it:      curl -s ${URL}/api/categories"
    echo "  Full demo:   API=${URL}/api python3 scripts/demo.py"
    echo
    echo "  Tunnel log:  ${LOG}"
    echo "  Stop it:     pkill -f 'cloudflared tunnel'"
    exit 0
  fi
  sleep 2
done

echo "Tunnel did not report a URL. Log:" >&2
tail -15 "$LOG" >&2
exit 1
