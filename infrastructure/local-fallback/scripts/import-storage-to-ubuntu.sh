#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "ERROR: falta .env. Ejecuta: bash scripts/sync-env-from-example.sh"
  exit 1
fi

require_env() {
  local name="$1"
  local value
  value="$(grep -E "^${name}=" .env | tail -n 1 | cut -d= -f2- || true)"
  if [ -z "${value}" ]; then
    echo "ERROR: falta ${name} en .env"
    exit 1
  fi
}

require_any_env() {
  local first="$1"
  local second="$2"
  local first_value second_value
  first_value="$(grep -E "^${first}=" .env | tail -n 1 | cut -d= -f2- || true)"
  second_value="$(grep -E "^${second}=" .env | tail -n 1 | cut -d= -f2- || true)"
  if [ -z "${first_value}" ] && [ -z "${second_value}" ]; then
    echo "ERROR: falta ${first} o ${second} en .env"
    exit 1
  fi
}

require_env "PUBLIC_BASE_URL"
require_env "SUPABASE_URL"
require_any_env "SUPABASE_SERVICE_ROLE_KEY" "SUPABASE_SECRET_KEY"

echo "==> Levantando servicios Ubuntu"
docker compose up -d --build

echo "==> Esperando API local"
for attempt in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:3001/ready >/dev/null; then
    break
  fi
  sleep 2
  if [ "$attempt" = "30" ]; then
    echo "ERROR: la API no respondio en http://127.0.0.1:3001/ready"
    docker compose ps
    docker compose logs --tail=120 api
    exit 1
  fi
done

echo "==> Simulando importacion de buckets"
docker compose run --rm api npm run import:supabase-storage -- --dry-run

echo "==> Copiando multimedia de Supabase Storage a Ubuntu"
docker compose run --rm api npm run import:supabase-storage

echo "==> Reescribiendo URLs de Supabase Storage hacia PUBLIC_BASE_URL"
docker compose run --rm api npm run rewrite:supabase-storage-urls -- --apply

echo "==> Reiniciando servicios"
docker compose up -d --force-recreate api edge realtime-gateway signaling

echo "==> Conteo de objetos locales por bucket"
docker compose exec -T db psql -U conectate -d conectate_fallback -c \
  "SELECT bucket_id, COUNT(*) AS files FROM fallback_storage_objects GROUP BY bucket_id ORDER BY bucket_id;"

echo "==> Verificacion"
curl -fsS http://127.0.0.1:3001/ready >/dev/null
curl -fsS http://127.0.0.1:3080/ready >/dev/null
echo "Storage importado y Ubuntu listo."
