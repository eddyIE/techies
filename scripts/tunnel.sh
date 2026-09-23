#!/usr/bin/env bash
# Expose the local stack on a public HTTPS URL.
#
#   ./scripts/tunnel.sh            ngrok, stable URL  (default)
#   ./scripts/tunnel.sh cloudflare Cloudflare quick tunnel, random URL
#
# ngrok is the default because its free dev domain is stable across restarts, so the mobile
# team can be given the URL once. The trade-off is a 20k request / 1 GB monthly cap, after
# which the endpoint stops until the next cycle. Cloudflare has no cap but issues a new random
# hostname on every reconnect.
#
# Requires the stack to be up:  docker compose up -d
set -euo pipefail

BACKEND="${1:-ngrok}"

cd "$(dirname "$0")/.."
PORT="${PORT:-8080}"
LOG="${LOG:-/tmp/techies-tunnel.log}"

if [ "$BACKEND" = "ngrok" ]; then
  command -v ngrok >/dev/null || { echo "ngrok not installed: brew install ngrok" >&2; exit 1; }
else
  command -v cloudflared >/dev/null || { echo "cloudflared not installed: brew install cloudflared" >&2; exit 1; }
fi

if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${PORT}/api/categories" || true)" != "200" ]; then
  echo "The stack is not answering on :${PORT}. Start it first:" >&2
  echo "  docker compose up -d" >&2
  exit 1
fi

pkill -f "cloudflared tunnel" 2>/dev/null || true
pkill -f "ngrok" 2>/dev/null || true
sleep 2
: > "$LOG"

if [ "$BACKEND" = "ngrok" ]; then
  # The endpoint name and its stable dev domain live in ngrok.yml.
  nohup ngrok start techies-api --log=stdout > "$LOG" 2>&1 &
  for _ in $(seq 1 30); do
    URL=$(curl -s --max-time 3 http://127.0.0.1:4040/api/tunnels 2>/dev/null \
      | python3 -c "import sys,json
try:
    d=json.load(sys.stdin)
    print(next(t['public_url'] for t in d.get('tunnels',[]) if t.get('proto')=='https'))
except Exception: pass" 2>/dev/null || true)
    if [ -n "${URL:-}" ]; then
      echo "$URL" > /tmp/techies-tunnel-url
      echo
      echo "  Public API:  ${URL}/api        (stable -- safe to hand to the frontend team)"
      echo "  Try it:      curl -s ${URL}/api/categories"
      echo "  Full demo:   API=${URL}/api python3 scripts/demo.py"
      echo "  Inspector:   http://127.0.0.1:4040   (replay every request the app made)"
      echo
      echo "  Stop it:     pkill -f ngrok"
      exit 0
    fi
    sleep 2
  done
  echo "ngrok did not report a URL. Log:" >&2
  tail -15 "$LOG" >&2
  exit 1
fi

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
