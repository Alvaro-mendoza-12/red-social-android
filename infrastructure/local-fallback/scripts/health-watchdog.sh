#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_ROOT="${BACKUP_ROOT:-$HOME/conectate-backups}"
MAX_DISK_PERCENT="${MAX_DISK_PERCENT:-85}"
EXPECTED_SERVICES=(db api ai-chatbot signaling edge realtime-gateway)
COMPOSE_LOCK="${CONECTATE_COMPOSE_LOCK:-$HOME/.cache/conectate-compose.lock}"

mkdir -p "$(dirname "$COMPOSE_LOCK")"
cd "$ROOT_DIR"

health_state() {
  local readiness running service ai_container updater
  readiness="$(curl -sS --max-time 8 -o /dev/null -w '%{http_code}' http://127.0.0.1:3080/ready)" || return 1
  case "$readiness" in
    200) ;;
    503) curl -fsS --max-time 8 http://127.0.0.1:3080/health >/dev/null || return 1 ;;
    *) return 1 ;;
  esac
  docker compose exec -T db pg_isready -U conectate -d conectate_fallback >/dev/null || return 1
  running="$(docker compose ps --status running --services)" || return 1
  for service in "${EXPECTED_SERVICES[@]}"; do
    grep -Fxq "$service" <<<"$running" || return 1
  done
  ai_container="$(docker compose ps -q ai-chatbot)" || return 1
  [ -n "$ai_container" ] || return 1
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$ai_container" 2>/dev/null)" = "healthy" ] || return 1

  [ "$readiness" = "200" ] && return 0
  grep -Fxq storage-antivirus <<<"$running" || return 1
  grep -Fxq storage-antivirus-updater <<<"$running" || return 1
  updater="$(docker compose ps -q storage-antivirus-updater)" || return 1
  [ -n "$updater" ] || return 1
  [ "$(docker inspect --format '{{.State.Health.Status}}' "$updater" 2>/dev/null)" = "unhealthy" ] || return 1
  return 2
}

fail_storage_readiness() {
  echo "ERROR: backend disponible con /ready 503 y actualizador de firmas degradado; se omite la reconciliacion del stack"
  exit 1
}

state=0
health_state || state=$?
if [ "$state" -eq 1 ]; then
  echo "WARN: stack degradado; intentando recuperacion controlada"
  (
    flock --wait 240 9 || exit 1
    # A deployment can finish while this watchdog waits for the same lock.
    # Recheck before mutating: stale failure evidence must not interrupt a
    # recovered proxy and its active WebSocket/media sessions.
    if health_state; then
      echo "RECOVERY_SKIPPED: stack recuperado mientras se esperaba el bloqueo"
    elif [ "$?" -eq 2 ]; then
      fail_storage_readiness
    else
      docker compose up -d --remove-orphans
      # Recovery can recreate upstream containers; refresh their DNS while
      # the same lock is held, without stopping healthy proxy workers.
      if ! docker compose exec -T edge nginx -t ||
         ! docker compose exec -T edge nginx -s reload; then
        docker compose restart edge
      fi
    fi
  ) 9>"$COMPOSE_LOCK"
  for _ in $(seq 1 12); do
    sleep 5
    state=0
    health_state || state=$?
    [ "$state" -eq 1 ] || break
  done
fi

if [ "$state" -eq 2 ]; then
  fail_storage_readiness
fi

if [ "$state" -ne 0 ]; then
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
