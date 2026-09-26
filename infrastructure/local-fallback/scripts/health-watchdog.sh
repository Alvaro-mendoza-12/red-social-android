#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_ROOT="${BACKUP_ROOT:-$HOME/conectate-backups}"
MAX_DISK_PERCENT="${MAX_DISK_PERCENT:-85}"
EXPECTED_SERVICES=(db api ai-chatbot signaling edge realtime-gateway)
COMPOSE_LOCK="${CONECTATE_COMPOSE_LOCK:-$HOME/.cache/conectate-compose.lock}"

mkdir -p "$(dirname "$COMPOSE_LOCK")"
cd "$ROOT_DIR"

healthy() {
  curl -fsS --max-time 8 http://127.0.0.1:3080/ready >/dev/null || return 1
  docker compose exec -T db pg_isready -U conectate -d conectate_fallback >/dev/null || return 1
  local service
  for service in "${EXPECTED_SERVICES[@]}"; do
    docker compose ps --status running --services | grep -Fxq "$service" || return 1
  done
  local ai_container
  ai_container="$(docker compose ps -q ai-chatbot)"
  [ -n "$ai_container" ] || return 1
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$ai_container" 2>/dev/null)" = "healthy" ] || return 1
}

if ! healthy; then
  echo "WARN: stack degradado; intentando recuperacion controlada"
  (
    flock --wait 240 9 || exit 1
    docker compose up -d --remove-orphans
    # Compose can recreate API/realtime/signaling with new container IPs.
    # Nginx resolves those names at worker start, so recovery is incomplete
    # until edge is reloaded while the same lock is still held.
    docker compose restart edge
  ) 9>"$COMPOSE_LOCK"
  for _ in $(seq 1 12); do
    sleep 5
    healthy && break
  done
fi

if ! healthy; then
  echo "ERROR: backend Ubuntu no se recupero"
  docker compose ps
  docker compose logs --tail=80 api ai-chatbot edge signaling realtime-gateway
  exit 1
fi

disk_percent="$(df -P "$ROOT_DIR" | awk 'NR==2 {gsub(/%/, "", $5); print $5}')"
if [ "${disk_percent:-0}" -ge "$MAX_DISK_PERCENT" ]; then
  echo "WARN: disco al ${disk_percent}% (umbral ${MAX_DISK_PERCENT}%)"
fi

if [ -L "$BACKUP_ROOT/latest" ]; then
  latest_epoch="$(stat -c %Y "$(readlink -f "$BACKUP_ROOT/latest")")"
  age_hours="$(( ($(date +%s) - latest_epoch) / 3600 ))"
  if [ "$age_hours" -gt 36 ]; then
    echo "WARN: ultima copia verificada tiene ${age_hours} horas"
  fi
else
  echo "WARN: aun no existe una copia verificada"
fi

echo "HEALTH_OK=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
