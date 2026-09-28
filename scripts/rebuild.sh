#!/usr/bin/env bash
# Rebuild one or more services and block until Docker reports them healthy.
#
# `docker compose up -d --build` returns once the container has *started*, which is long
# before Spring has run Flyway and opened the port, so anything after it races the startup.
# Every service in docker-compose.yml already has an actuator healthcheck, so waiting on
# that is both simpler and more honest than grepping the logs for a startup line.
#
#   scripts/rebuild.sh order-service
#   scripts/rebuild.sh order-service catalog-service
#   scripts/rebuild.sh --no-build order-service    # restart only
#   WAIT_TIMEOUT=300 scripts/rebuild.sh ai-service
set -euo pipefail

cd "$(dirname "$0")/.."

TIMEOUT="${WAIT_TIMEOUT:-240}"
BUILD=1
if [[ "${1:-}" == "--no-build" ]]; then BUILD=0; shift; fi

if [[ $# -eq 0 ]]; then
  echo "usage: scripts/rebuild.sh [--no-build] <service> [service...]" >&2
  exit 2
fi

if [[ $BUILD -eq 1 ]]; then
  echo "==> maven: $*"
  ./mvnw -o -q -pl "$(printf '%s,' "$@" | sed 's/,$//')" -DskipTests package 2>&1 | tail -5
fi

echo "==> compose up: $*"
if [[ $BUILD -eq 1 ]]; then
  docker compose up -d --build "$@" >/dev/null
else
  docker compose up -d "$@" >/dev/null
fi

health() {
  local cid
  cid=$(docker compose ps -q "$1" 2>/dev/null) || return 1
  [[ -n "$cid" ]] || { echo missing; return 0; }
  # No healthcheck configured reports <nil>; treat a running container as good enough.
  local h running
  h=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo missing)
  running=$(docker inspect -f '{{.State.Running}}' "$cid" 2>/dev/null || echo false)
  if [[ "$h" == "none" ]]; then
    [[ "$running" == "true" ]] && echo healthy || echo exited
  else
    [[ "$running" == "true" ]] || { echo exited; return 0; }
    echo "$h"
  fi
}

fail=0
for svc in "$@"; do
  printf '==> %s ' "$svc"
  deadline=$(( $(date +%s) + TIMEOUT ))
  state=starting
  while [[ $(date +%s) -lt $deadline ]]; do
    state=$(health "$svc")
    case "$state" in
      healthy) break ;;
      unhealthy|exited|missing) break ;;
      *) printf '.'; sleep 2 ;;
    esac
  done

  if [[ "$state" == healthy ]]; then
    # Surface the schema version so a migration that silently did not run is visible.
    migrated=$(docker compose logs "$svc" --since 10m 2>/dev/null \
               | grep -oE 'now at version v[0-9]+' | tail -1 || true)
    echo " healthy${migrated:+  (}${migrated}${migrated:+)}"
  else
    echo " FAILED (${state})"
    docker compose logs "$svc" --tail 30 2>/dev/null \
      | grep -vE '\[InventoryClient#|\[CatalogClient#|\[IdentityClient#' | tail -20
    fail=1
  fi
done

exit $fail
