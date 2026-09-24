#!/usr/bin/env bash
# Bring the whole thing up, detached, and print the public URL.
#
#   ./scripts/start.sh              stack + ngrok tunnel
#   ./scripts/start.sh --no-tunnel  stack only, local access at :8080
#
# Everything runs in the background and survives closing the terminal. Stop it with
# ./scripts/stop.sh
set -uo pipefail
cd "$(dirname "$0")/.."

TUNNEL=yes
[ "${1:-}" = "--no-tunnel" ] && TUNNEL=no


# Stop any ngrok agent properly. A backgrounded agent can end up SUSPENDED (ps STAT "T"),
# in which case it ignores SIGTERM but still holds port 4040, so the next agent fails with
# "bind: address already in use". Escalate to SIGKILL and wait for the port to clear.
stop_ngrok() {
  pkill -f "ngrok start" 2>/dev/null || true
  sleep 2
  if pgrep -f "ngrok start" >/dev/null 2>&1; then
    pkill -9 -f "ngrok start" 2>/dev/null || true
    sleep 2
  fi
  for _ in $(seq 1 10); do
    lsof -nP -iTCP:4040 -sTCP:LISTEN >/dev/null 2>&1 || return 0
    sleep 1
  done
}

# Start detached. stdin from /dev/null matters: a background process that still has the
# terminal as stdin gets SIGTTIN and is suspended, which is how the agent ends up in "T".
start_ngrok() {
  nohup ngrok start techies-api --log=stdout < /dev/null > /tmp/techies-tunnel.log 2>&1 &
}

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

# ---- 1. Docker daemon ------------------------------------------------------
say "1/4  Docker"
if docker info >/dev/null 2>&1; then
  ok "daemon already running"
else
  warn "daemon down, starting Docker Desktop (this takes ~30s)"
  open -a Docker 2>/dev/null || die "could not launch Docker Desktop"
  for _ in $(seq 1 60); do
    docker info >/dev/null 2>&1 && break
    sleep 3
  done
  docker info >/dev/null 2>&1 || die "Docker did not start in time"
  ok "daemon up"
fi

# ---- 2. The stack ----------------------------------------------------------
say "2/4  Services"
docker compose up -d >/dev/null 2>&1 || die "docker compose up failed"
printf '  waiting for 7 containers to report healthy (up to 4 min)'
for _ in $(seq 1 80); do
  HEALTHY=$(docker compose ps --format '{{.Health}}' 2>/dev/null | grep -cx healthy || true)
  [ "${HEALTHY:-0}" -ge 7 ] && break
  printf '.'
  sleep 3
done
printf '\n'
[ "${HEALTHY:-0}" -ge 7 ] && ok "all 7 healthy" || warn "only ${HEALTHY:-0}/7 healthy — check: docker compose ps"

# ---- 3. Gateway routing ----------------------------------------------------
# Containers report healthy before they finish registering with Eureka, so the gateway
# answers 503 for a short window after start. Wait for real routing, not just health.
say "3/4  Gateway routing"
printf '  waiting for Eureka registration (up to 2 min)'
for _ in $(seq 1 40); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://localhost:8080/api/categories 2>/dev/null)
  [ "$CODE" = "200" ] && break
  printf '.'
  sleep 3
done
printf '\n'
[ "${CODE:-}" = "200" ] && ok "http://localhost:8080/api is serving" \
                        || warn "gateway returned ${CODE:-no response} — give it a moment and retry"

# ---- 4. Tunnel -------------------------------------------------------------
if [ "$TUNNEL" = "no" ]; then
  say "4/4  Tunnel"
  ok "skipped (--no-tunnel)"
  printf '\n  Local API: http://localhost:8080/api\n\n'
  exit 0
fi

say "4/4  Public tunnel"
if ! command -v ngrok >/dev/null 2>&1; then
  warn "ngrok not installed (brew install ngrok) — stack is up locally"
  exit 0
fi

# A live process is not the same as a live tunnel: an ngrok agent can sit there with a
# dead session, in which case reusing it means waiting forever for a URL that never comes.
# Ask the agent API whether it actually has a tunnel.
tunnel_url() {
  curl -s --max-time 3 http://127.0.0.1:4040/api/tunnels 2>/dev/null \
    | python3 -c "import sys,json
try:
    d=json.load(sys.stdin)
    print(next(t['public_url'] for t in d.get('tunnels',[]) if t.get('proto')=='https'))
except Exception: pass" 2>/dev/null
}

if [ -n "$(tunnel_url)" ]; then
  ok "ngrok already serving, reusing it"
else
  if pgrep -f "ngrok start" >/dev/null 2>&1; then
    warn "found a stale ngrok with no live tunnel, replacing it"
    stop_ngrok
  fi
  start_ngrok
  ok "ngrok started detached (pid $!)"
fi

printf '  waiting for the tunnel (up to 40s)'
for _ in $(seq 1 20); do
  URL=$(tunnel_url)
  [ -n "${URL:-}" ] && break
  printf '.'
  sleep 2
done
printf '\n'

if [ -z "${URL:-}" ]; then
  if grep -q "address already in use" /tmp/techies-tunnel.log 2>/dev/null; then
    warn "port 4040 is still held by another process:"
    lsof -nP -iTCP:4040 -sTCP:LISTEN 2>/dev/null | tail -1 | sed 's/^/      /'
    warn "kill it with: kill -9 <pid>   then re-run this script"
  else
    warn "no tunnel URL yet — check /tmp/techies-tunnel.log"
    tail -3 /tmp/techies-tunnel.log 2>/dev/null | sed 's/^/      /'
  fi
  exit 0
fi
echo "$URL" > /tmp/techies-tunnel-url
ok "tunnel up"

# Confirm the public URL actually serves, rather than assuming.
PUB=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$URL/api/categories" 2>/dev/null)
[ "$PUB" = "200" ] && ok "public URL verified serving" || warn "public URL returned $PUB"

cat <<SUMMARY

  ────────────────────────────────────────────────────────────────
  Public API   ${URL}/api
  Local API    http://localhost:8080/api
  Inspector    http://127.0.0.1:4040      (replay every request)

  Demo         API=${URL}/api python3 scripts/demo.py
  Logs         tail -f logs/order-service-error.log
  Stop         ./scripts/stop.sh
  ────────────────────────────────────────────────────────────────

  Note: traffic is restricted to Vietnam. Anyone abroad gets a 403.

SUMMARY
