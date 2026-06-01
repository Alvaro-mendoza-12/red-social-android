#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="${ROOT_DIR:-$HOME/conectate-local-fallback}"
cd "$ROOT_DIR"

echo "==> Directorio"
pwd

echo
echo "==> Archivos clave"
for file in \
  docker-compose.yml \
  .env \
  api/package.json \
  api/src/server.js \
  api/src/data-routes.js \
  api/src/storage-routes.js \
  scripts/repair-ubuntu-primary.sh
do
  if [ -e "$file" ]; then
    ls -lh "$file"
  else
    echo "FALTA: $file"
  fi
done

echo
echo "==> Buscando rutas incompatibles con Express 5"
if grep -RInE ":[A-Za-z0-9_]+\\([^)]*\\)" api/src; then
  echo "ERROR: hay rutas tipo :param(valor1|valor2). Deben reemplazarse por rutas separadas." >&2
else
  echo "OK: no hay rutas Express 5 incompatibles detectadas en api/src"
fi

echo
echo "==> Versiones"
docker --version || true
docker compose version || true

echo
echo "==> Configuracion Docker Compose"
docker compose config --quiet && echo "OK: compose valido"

echo
echo "==> Estado actual"
docker compose ps || true

echo
echo "==> Validacion de sintaxis dentro de la imagen api"
docker compose build api
docker compose run --rm --no-deps api node --check src/server.js
docker compose run --rm --no-deps api node --check src/data-routes.js
docker compose run --rm --no-deps api node --check src/storage-routes.js

echo
echo "==> Validacion de registro de rutas Express dentro de la imagen api"
docker compose run --rm --no-deps -e ROUTE_CHECK_ONLY=1 api node src/server.js

echo
echo "==> Salud de Postgres"
docker compose up -d db
docker compose exec -T db pg_isready -U conectate -d conectate_fallback || true
postgres_password="$(grep -E '^POSTGRES_PASSWORD=' .env | tail -1 | cut -d= -f2- || true)"
if [ -n "$postgres_password" ]; then
  echo "==> Prueba de autenticacion Postgres TCP con POSTGRES_PASSWORD de .env"
  docker compose exec -T \
    -e PGPASSWORD="$postgres_password" \
    db psql -h 127.0.0.1 -U conectate -d conectate_fallback -Atc "select 'postgres_auth_ok';" || true
else
  echo "ERROR: POSTGRES_PASSWORD esta vacio en .env"
fi
docker compose exec -T db psql -U conectate -d conectate_fallback -Atc "
select 'fallback_users,' || count(*) from fallback_users
union all select 'supabase_import_tables,' || count(*) from pg_tables where schemaname = 'supabase_import'
union all select 'storage_objects,' || count(*) from fallback_storage_objects
union all select 'fallback_messages,' || count(*) from fallback_messages
union all select 'fallback_room_messages,' || count(*) from fallback_room_messages;
" || true

echo
echo "==> Intentando levantar api"
docker compose up -d api
sleep 20
docker compose ps

echo
echo "==> Logs api"
docker compose logs --tail=240 api || true

echo
echo "==> Ready local"
curl -i http://127.0.0.1:3001/ready || true
