#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="${ROOT_DIR:-$HOME/conectate-local-fallback}"
PUBLIC_BASE_URL_INPUT="${PUBLIC_BASE_URL:-}"
FALLBACK_EDGE_PORT_INPUT="${FALLBACK_EDGE_PORT:-3080}"

cd "$ROOT_DIR"

print_service_logs() {
  echo
  echo "==> Estado Docker"
  docker compose ps || true
  echo
  echo "==> Logs api"
  docker compose logs --tail=180 api || true
  echo
  echo "==> Logs realtime/signaling/edge"
  docker compose logs --tail=80 realtime-gateway signaling edge || true
}

fail_with_logs() {
  echo "ERROR: $*" >&2
  print_service_logs
  exit 1
}

require_file() {
  local file="$1"
  if [ ! -f "$file" ]; then
    fail_with_logs "falta $file. Copia de nuevo infrastructure/local-fallback completo a Ubuntu."
  fi
}

if [ ! -f ".env" ]; then
  cp config.example .env
fi

require_file "docker-compose.yml"
require_file "api/package.json"
require_file "api/src/server.js"
require_file "api/src/data-routes.js"
require_file "api/src/storage-routes.js"

if grep -RInE ":[A-Za-z0-9_]+\\([^)]*\\)" api/src; then
  fail_with_logs "hay rutas Express 5 incompatibles en api/src. Copia la version actual del repo antes de levantar."
fi

set_env_value() {
  local key="$1"
  local value="$2"
  if grep -q "^${key}=" .env; then
    sed -i "s|^${key}=.*|${key}=${value}|" .env
  else
    printf '%s=%s\n' "$key" "$value" >> .env
  fi
}

ensure_secret() {
  local key="$1"
  local current
  current="$(grep -E "^${key}=" .env | tail -1 | cut -d= -f2- || true)"
  if [ -z "$current" ] || [[ "$current" == change-this-* ]]; then
    set_env_value "$key" "$(openssl rand -hex 32)"
  fi
}

ensure_secret POSTGRES_PASSWORD
ensure_secret JWT_SECRET
ensure_secret ADMIN_SYNC_TOKEN
set_env_value FALLBACK_EDGE_PORT "$FALLBACK_EDGE_PORT_INPUT"

configured_signaling_context="$(grep -E '^SIGNALING_CONTEXT=' .env | tail -1 | cut -d= -f2- || true)"
if [ -z "$configured_signaling_context" ] || [ ! -d "$configured_signaling_context" ]; then
  if [ -d "$ROOT_DIR/../signaling-server" ]; then
    set_env_value SIGNALING_CONTEXT "$ROOT_DIR/../signaling-server"
  elif [ -d "$ROOT_DIR/../../signaling-server" ]; then
    set_env_value SIGNALING_CONTEXT "$ROOT_DIR/../../signaling-server"
  else
    echo "ERROR: falta el directorio signaling-server para voz/llamadas." >&2
    echo "Copia tambien la carpeta signaling-server a Ubuntu, por ejemplo:" >&2
    echo "scp -P 2222 -r signaling-server alvaro@127.0.0.1:~/signaling-server" >&2
    exit 1
  fi
fi

if [ -n "$PUBLIC_BASE_URL_INPUT" ]; then
  set_env_value PUBLIC_BASE_URL "${PUBLIC_BASE_URL_INPUT%/}"
fi

mkdir -p imports logs

echo "==> Validando JavaScript del backend"
docker compose build api
docker compose run --rm --no-deps api node --check src/server.js
docker compose run --rm --no-deps api node --check src/data-routes.js
docker compose run --rm --no-deps api node --check src/storage-routes.js

echo "==> Aplicando migraciones idempotentes"
docker compose up -d db
for migration in db/migrations/*.sql; do
  [ -f "$migration" ] || continue
  echo "---- $migration"
  docker compose exec -T db psql -U conectate -d conectate_fallback < "$migration"
done

echo "==> Reconstruyendo servicios principales"
docker compose up -d --build

wait_http() {
  local name="$1"
  local url="$2"
  local tries="${3:-60}"
  local i
  for i in $(seq 1 "$tries"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "OK $name -> $url"
      return 0
    fi
    sleep 2
  done
  fail_with_logs "$name no respondio: $url"
}

wait_http "api" "http://127.0.0.1:3001/ready"
wait_http "edge" "http://127.0.0.1:${FALLBACK_EDGE_PORT_INPUT}/ready"
wait_http "realtime" "http://127.0.0.1:3002/health"

echo "==> Estado Docker"
docker compose ps

echo "==> Conteos principales"
docker compose exec -T db psql -U conectate -d conectate_fallback -Atc "
select 'fallback_users,' || count(*) from fallback_users
union all select 'imported_tables,' || count(*) from pg_tables where schemaname = 'supabase_import'
union all select 'storage_objects,' || count(*) from fallback_storage_objects
union all select 'local_chats,' || count(*) from fallback_messages
union all select 'local_room_messages,' || count(*) from fallback_room_messages
union all select 'local_groups_communities,' || count(*) from fallback_collection_rooms
union all select 'local_voice_rooms,' || count(*) from fallback_voice_rooms;
"

echo "==> Logs recientes"
docker compose logs --tail=80 api signaling edge

echo
echo "Ubuntu esta levantado. Para exponer todo con una sola URL publica, abre otra terminal SSH y ejecuta:"
echo "ngrok http ${FALLBACK_EDGE_PORT_INPUT}"
echo
echo "Si usas dominio fijo de ngrok, ejecuta este script asi:"
echo "PUBLIC_BASE_URL=https://TU-DOMINIO.ngrok-free.dev bash scripts/repair-ubuntu-primary.sh"
