#!/usr/bin/env bash
# Stop the tunnel and the stack.
#
#   ./scripts/stop.sh           stop containers, keep data
#   ./scripts/stop.sh --clean   also delete the database volume (reseeds on next start)
set -uo pipefail
cd "$(dirname "$0")/.."

ok() { printf '  \033[32m✓\033[0m %s\n' "$*"; }

printf '\n\033[1mStopping\033[0m\n'

if pgrep -f "ngrok start" >/dev/null 2>&1; then
  pkill -f "ngrok start" 2>/dev/null || true
  sleep 2
  # A suspended agent (ps STAT "T") ignores SIGTERM but keeps holding port 4040.
  if pgrep -f "ngrok start" >/dev/null 2>&1; then
    pkill -9 -f "ngrok start" 2>/dev/null || true
    sleep 1
  fi
  ok "ngrok stopped"
else
  ok "ngrok was not running"
fi

if [ "${1:-}" = "--clean" ]; then
  docker compose down -v >/dev/null 2>&1 && ok "containers removed and database volume deleted"
  printf '\n  Next start will reseed the catalogue from scratch.\n\n'
else
  docker compose stop >/dev/null 2>&1 && ok "containers stopped (data kept)"
  printf '\n  Restart with ./scripts/start.sh — it will be quick, images are already built.\n\n'
fi
