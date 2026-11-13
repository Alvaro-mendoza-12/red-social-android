#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="${ROOT_DIR:-$HOME/conectate-local-fallback}"
cd "$ROOT_DIR"
PUBLIC_BASE_URL_INPUT="${PUBLIC_BASE_URL:-}"
if [ -z "$PUBLIC_BASE_URL_INPUT" ] && [ -f ".env" ]; then
  PUBLIC_BASE_URL_INPUT="$(grep -E '^PUBLIC_BASE_URL=' .env | tail -1 | cut -d= -f2- || true)"
fi
PUBLIC_BASE_URL_INPUT="${PUBLIC_BASE_URL_INPUT%/}"

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
  api/src/postgrest-compat-routes.js \
  api/src/storage-routes.js \
  nginx/default.conf \
  scripts/repair-ubuntu-primary.sh
do
  if [ -e "$file" ]; then
    ls -lh "$file"
  else
    echo "FALTA: $file"
  fi
done

echo
echo "==> Configuracion edge/nginx"
if [ -f nginx/default.conf ]; then
  if grep -q "resolver 127.0.0.11" nginx/default.conf && grep -q 'conectate-local-fallback-api-main:3001' nginx/default.conf; then
    echo "OK: nginx/default.conf usa el contenedor fijo de api"
  else
    echo "ERROR: nginx/default.conf esta viejo. Copia infrastructure/local-fallback/nginx/default.conf desde el repo." >&2
  fi
  grep -nE "resolver|api_upstream|proxy_pass" nginx/default.conf || true
else
  echo "ERROR: falta nginx/default.conf" >&2
fi

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
docker compose run --rm --no-deps api node --check src/postgrest-compat-routes.js
docker compose run --rm --no-deps api node --check src/storage-routes.js
docker ps -aq --filter "name=conectate-local-fallback-api-run" | xargs -r docker rm -f >/dev/null 2>&1 || true

echo
echo "==> Validacion de registro de rutas Express dentro de la imagen api"
docker compose run --rm --no-deps -e ROUTE_CHECK_ONLY=1 api node src/server.js
docker ps -aq --filter "name=conectate-local-fallback-api-run" | xargs -r docker rm -f >/dev/null 2>&1 || true

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

echo
echo "==> Ready interno dentro del contenedor api"
docker compose exec -T api node -e "fetch('http://127.0.0.1:3001/ready').then(async r=>{console.log(r.status, await r.text()); process.exit(r.ok?0:1)}).catch(e=>{console.error(e); process.exit(1)})" || true

echo
echo "==> Ready interno edge -> api por red Docker"
docker compose exec -T edge wget -S -O- http://conectate-local-fallback-api-main:3001/ready || true

echo
echo "==> Ready edge/ngrok upstream local"
curl -i http://127.0.0.1:3080/ready || true

echo
echo "==> CORS preflight por edge local"
curl -i -X OPTIONS http://127.0.0.1:3080/v1/auth/login \
  -H "Origin: https://novvinapp.com" \
  -H "Access-Control-Request-Method: POST" || true
curl -i -X OPTIONS http://127.0.0.1:3080/v1/auth/web-login-pairing \
  -H "Origin: https://novvinapp.com" \
  -H "Access-Control-Request-Method: POST" || true
curl -i -X OPTIONS http://127.0.0.1:3080/functions/v1/web-login-pairing \
  -H "Origin: https://novvinapp.com" \
  -H "Access-Control-Request-Method: POST" || true

echo
echo "==> Ngrok/publico"
if [ -n "$PUBLIC_BASE_URL_INPUT" ]; then
  echo "PUBLIC_BASE_URL=$PUBLIC_BASE_URL_INPUT"
  curl -i -H "ngrok-skip-browser-warning: true" "$PUBLIC_BASE_URL_INPUT/ready" || true
  curl -i -X OPTIONS "$PUBLIC_BASE_URL_INPUT/v1/auth/login" \
    -H "Origin: https://novvinapp.com" \
    -H "Access-Control-Request-Method: POST" \
    -H "ngrok-skip-browser-warning: true" || true
  curl -i -X OPTIONS "$PUBLIC_BASE_URL_INPUT/v1/auth/web-login-pairing" \
    -H "Origin: https://novvinapp.com" \
    -H "Access-Control-Request-Method: POST" \
    -H "ngrok-skip-browser-warning: true" || true
else
  echo "PUBLIC_BASE_URL no esta definido. Ejecuta asi para probar ngrok:"
  echo "PUBLIC_BASE_URL=https://TU-DOMINIO.ngrok-free.dev bash scripts/diagnose-api.sh"
fi
