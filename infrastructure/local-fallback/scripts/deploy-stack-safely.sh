#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_BUILD=false
COMPOSE_LOCK="${CONECTATE_COMPOSE_LOCK:-$HOME/.cache/conectate-compose.lock}"

if [ "${1:-}" = "--skip-build" ]; then
  SKIP_BUILD=true
elif [ "$#" -gt 0 ]; then
  echo "Uso: $0 [--skip-build]" >&2
  exit 2
fi

mkdir -p "$(dirname "$COMPOSE_LOCK")"
exec 9>"$COMPOSE_LOCK"
flock --wait 600 9 || {
  echo "ERROR: otro proceso de Docker Compose sigue activo" >&2
  exit 1
}
cd "$ROOT_DIR"

wait_service() {
  local service="$1"
  local timeout_seconds="${2:-180}"
  local deadline=$((SECONDS + timeout_seconds))
  local container_id state health

  while [ "$SECONDS" -lt "$deadline" ]; do
    container_id="$(docker compose ps -q "$service" 2>/dev/null || true)"
    if [ -n "$container_id" ]; then
      state="$(docker inspect --format '{{.State.Status}}' "$container_id" 2>/dev/null || true)"
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id" 2>/dev/null || true)"
      if [ "$state" = "running" ] && { [ "$health" = "healthy" ] || [ "$health" = "none" ]; }; then
        echo "SERVICE_READY=$service state=$state health=$health"
        return 0
      fi
    fi
    sleep 5
  done

  echo "ERROR: $service no alcanzo estado saludable en ${timeout_seconds}s" >&2
  docker compose ps "$service" >&2 || true
  docker compose logs --tail=120 "$service" >&2 || true
  return 1
}

echo "==> Validando configuracion Docker Compose"
docker compose config --quiet

if [ "$SKIP_BUILD" = false ]; then
  echo "==> Construyendo imagenes sin interrumpir los contenedores actuales"
  docker compose build api ai-chatbot signaling realtime-gateway
fi

echo "==> Verificando base de datos antes de actualizar"
docker compose up -d db
wait_service db 120

echo "==> Aplicando migraciones versionadas antes de actualizar servicios"
docker compose exec -T db psql -U conectate -d conectate_fallback -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE IF NOT EXISTS fallback_schema_migrations (
  filename TEXT PRIMARY KEY,
  checksum_sha256 TEXT NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
SQL
for migration in db/migrations/*.sql; do
  [ -f "$migration" ] || continue
  filename="$(basename "$migration")"
  checksum="$(sha256sum "$migration" | awk '{print $1}')"
  recorded_checksum="$(docker compose exec -T db psql -U conectate -d conectate_fallback -Atc \
    "SELECT checksum_sha256 FROM fallback_schema_migrations WHERE filename = '$filename'")"
  if [ -n "$recorded_checksum" ]; then
    if [ "$recorded_checksum" != "$checksum" ]; then
      echo "ERROR: checksum de migracion ya aplicada no coincide: $filename" >&2
      exit 1
    fi
    echo "MIGRATION_ALREADY_APPLIED=$filename"
    continue
  fi
  echo "MIGRATION_APPLYING=$filename"
  docker compose exec -T db psql -U conectate -d conectate_fallback \
    -v ON_ERROR_STOP=1 -1 < "$migration"
  docker compose exec -T db psql -U conectate -d conectate_fallback \
    -v ON_ERROR_STOP=1 \
    -v migration_filename="$filename" \
    -v migration_checksum="$checksum" <<'SQL'
INSERT INTO fallback_schema_migrations(filename, checksum_sha256)
VALUES (:'migration_filename', :'migration_checksum');
SQL
  echo "MIGRATION_APPLIED=$filename"
done

for service in ai-chatbot realtime-gateway api signaling edge; do
  echo "==> Actualizando $service"
  docker compose up -d --no-deps --force-recreate "$service"
  if [ "$service" = "ai-chatbot" ]; then
    wait_service "$service" 300
  else
    wait_service "$service" 180
  fi
done

docker compose up -d --remove-orphans

echo "==> Verificacion funcional local"
curl -fsS --max-time 10 http://127.0.0.1:3080/ready >/dev/null
docker compose exec -T ai-chatbot node -e \
  "fetch('http://127.0.0.1:3010/ready').then(r=>{if(!r.ok)process.exit(1);return r.json()}).then(v=>{if(!v.ok)process.exit(1)}).catch(()=>process.exit(1))"
docker compose exec -T db pg_isready -U conectate -d conectate_fallback >/dev/null

echo "STACK_DEPLOY_OK=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
