#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="${ROOT_DIR:-$HOME/conectate-local-fallback}"
cd "$ROOT_DIR"

timestamp="$(date +%Y%m%d-%H%M%S)"
backup_dir="$ROOT_DIR/backups/storage-$timestamp"
mkdir -p "$backup_dir" logs

fail() {
  echo "ERROR: $*" >&2
  echo
  echo "==> Estado Docker"
  docker compose ps || true
  echo
  echo "==> Logs API"
  docker compose logs --tail=120 api || true
  exit 1
}

get_env_value() {
  local key="$1"
  grep -E "^${key}=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true
}

set_env_value() {
  local key="$1"
  local value="$2"
  if grep -q "^${key}=" .env; then
    sed -i "s|^${key}=.*|${key}=${value}|" .env
  else
    printf '%s=%s\n' "$key" "$value" >> .env
  fi
}

require_env() {
  local key="$1"
  local value
  value="$(get_env_value "$key")"
  if [ -z "$value" ]; then
    fail "falta ${key} en .env"
  fi
}

require_any_env() {
  local first="$1"
  local second="$2"
  local first_value second_value
  first_value="$(get_env_value "$first")"
  second_value="$(get_env_value "$second")"
  if [ -z "$first_value" ] && [ -z "$second_value" ]; then
    fail "falta ${first} o ${second} en .env"
  fi
}

if [ ! -f .env ]; then
  if [ -f config.example ]; then
    cp config.example .env
  else
    fail "no existe .env ni config.example"
  fi
fi

if [ -n "${PUBLIC_BASE_URL:-}" ]; then
  set_env_value PUBLIC_BASE_URL "${PUBLIC_BASE_URL%/}"
fi
if [ -n "${SUPABASE_URL:-}" ]; then
  set_env_value SUPABASE_URL "${SUPABASE_URL%/}"
fi
if [ -n "${SUPABASE_SECRET_KEY:-}" ]; then
  set_env_value SUPABASE_SECRET_KEY "$SUPABASE_SECRET_KEY"
fi
if [ -n "${SUPABASE_SERVICE_ROLE_KEY:-}" ]; then
  set_env_value SUPABASE_SERVICE_ROLE_KEY "$SUPABASE_SERVICE_ROLE_KEY"
fi
if [ -n "${SUPABASE_STORAGE_BUCKETS:-}" ]; then
  set_env_value SUPABASE_STORAGE_BUCKETS "$SUPABASE_STORAGE_BUCKETS"
fi

require_env "POSTGRES_PASSWORD"
require_env "PUBLIC_BASE_URL"
require_env "SUPABASE_URL"
require_any_env "SUPABASE_SERVICE_ROLE_KEY" "SUPABASE_SECRET_KEY"

echo "==> Rescate urgente de Supabase Storage"
echo "Proyecto local: $ROOT_DIR"
echo "PUBLIC_BASE_URL: $(get_env_value PUBLIC_BASE_URL)"
if [ -n "$(get_env_value SUPABASE_STORAGE_BUCKETS)" ]; then
  echo "Buckets limitados: $(get_env_value SUPABASE_STORAGE_BUCKETS)"
else
  echo "Buckets: todos los disponibles en Supabase"
fi
echo

echo "==> Levantando Postgres y API local"
docker compose up -d db
for i in $(seq 1 60); do
  if docker compose exec -T db pg_isready -U conectate -d conectate_fallback >/dev/null 2>&1; then
    break
  fi
  sleep 2
  [ "$i" = "60" ] && fail "Postgres no respondio"
done

docker compose build api
docker compose up -d api
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:3001/ready >/dev/null 2>&1; then
    echo "OK API local lista"
    break
  fi
  sleep 2
  [ "$i" = "60" ] && fail "API local no respondio"
done

echo
echo "==> Descargando archivos de Supabase hacia el volumen de Ubuntu"
echo "    Esto puede demorar. Si se corta por cuota, vuelve a ejecutar este mismo script y continua donde quedo."
docker compose run --rm api npm run import:supabase-storage | tee "logs/storage-import-$timestamp.log"

echo
echo "==> Reescribiendo URLs antiguas de Supabase hacia Ubuntu/ngrok"
docker compose run --rm \
  -e SUPABASE_STORAGE_REWRITE_SCHEMAS="${SUPABASE_STORAGE_REWRITE_SCHEMAS:-public,supabase_import}" \
  api npm run rewrite:supabase-storage-urls -- --apply | tee "logs/storage-rewrite-$timestamp.log"

echo
echo "==> Guardando backup comprimido del storage local"
docker run --rm \
  -v conectate-fallback-storage:/data/storage:ro \
  -v "$backup_dir:/backup" \
  alpine:3.20 \
  tar -czf "/backup/conectate-storage-$timestamp.tar.gz" -C /data/storage .

echo
echo "==> Conteo de objetos importados por bucket"
docker compose exec -T db psql -U conectate -d conectate_fallback -Atc \
  "SELECT bucket_id || ',' || COUNT(*) || ',' || COALESCE(SUM(size_bytes),0) FROM fallback_storage_objects GROUP BY bucket_id ORDER BY bucket_id;" \
  | tee "$backup_dir/storage-counts.csv"

echo
echo "==> Verificacion rapida"
curl -fsS http://127.0.0.1:3001/ready >/dev/null || fail "API dejo de responder despues de importar"

echo
echo "LISTO: storage rescatado/importado en Ubuntu."
echo "Backup: $backup_dir/conectate-storage-$timestamp.tar.gz"
echo "Logs:"
echo "  $ROOT_DIR/logs/storage-import-$timestamp.log"
echo "  $ROOT_DIR/logs/storage-rewrite-$timestamp.log"
echo
echo "Ahora levanta todo el stack y ngrok:"
echo "  PUBLIC_BASE_URL=$(get_env_value PUBLIC_BASE_URL) bash scripts/repair-ubuntu-primary.sh"
echo "  ngrok http 3080"
