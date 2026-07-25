#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_LOCK="${CONECTATE_COMPOSE_LOCK:-$HOME/.cache/conectate-compose.lock}"
MAX_ATTEMPTS="${CONECTATE_STACK_START_ATTEMPTS:-3}"
LOCK_WAIT_SECONDS="${CONECTATE_COMPOSE_LOCK_WAIT_SECONDS:-240}"

mkdir -p "$(dirname "$COMPOSE_LOCK")"
cd "$ROOT_DIR"

for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
  echo "STACK_START_ATTEMPT=$attempt/$MAX_ATTEMPTS"
  if flock --wait "$LOCK_WAIT_SECONDS" "$COMPOSE_LOCK" \
    docker compose up -d --remove-orphans; then
    echo "STACK_START_OK=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    exit 0
  fi

  if [ "$attempt" -lt "$MAX_ATTEMPTS" ]; then
    sleep "$((attempt * 10))"
  fi
done

echo "ERROR: Docker Compose no pudo estabilizar el stack tras $MAX_ATTEMPTS intentos" >&2
docker compose ps >&2 || true
docker compose logs --tail=100 api ai-chatbot edge signaling realtime-gateway >&2 || true
exit 1
