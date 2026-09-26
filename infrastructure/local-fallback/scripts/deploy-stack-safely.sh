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

redis_secret_path="./secrets/signaling-redis-password.txt"
if [ -f .env ]; then
  configured_redis_secret_path="$(sed -n 's/^SIGNALING_REDIS_PASSWORD_FILE=//p' .env | tail -n 1 | tr -d '\r' || true)"
  configured_redis_secret_path="${configured_redis_secret_path%\"}"
  configured_redis_secret_path="${configured_redis_secret_path#\"}"
  if [ -n "$configured_redis_secret_path" ]; then
    redis_secret_path="$configured_redis_secret_path"
  fi
fi
if [[ "$redis_secret_path" != /* ]]; then
  redis_secret_path="$ROOT_DIR/${redis_secret_path#./}"
fi
mkdir -p "$(dirname "$redis_secret_path")"
chmod 700 "$(dirname "$redis_secret_path")"
if [ ! -s "$redis_secret_path" ]; then
  umask 077
  openssl rand -hex 32 > "$redis_secret_path"
  echo "CREATED_SIGNALING_REDIS_SECRET=$redis_secret_path"
fi
# Docker Compose implements file-backed secrets as bind mounts. Under a
# user-namespaced daemon, mode 0600 owned by the host user is unreadable both
# to container root and to the non-root signaling process. The containing host
# directory remains 0700, while the mounted file is read-only and visible only
# to the two services that explicitly request this secret.
chmod 644 "$redis_secret_path"

live_internal_events_token="$(sed -n 's/^LIVE_INTERNAL_EVENTS_TOKEN=//p' .env 2>/dev/null | tail -n 1 | tr -d '\r"' || true)"
if [ "${#live_internal_events_token}" -lt 32 ] || [[ "$live_internal_events_token" == change-this-* ]]; then
  live_internal_events_token="$(openssl rand -hex 32)"
  if grep -q '^LIVE_INTERNAL_EVENTS_TOKEN=' .env 2>/dev/null; then
    sed -i "s#^LIVE_INTERNAL_EVENTS_TOKEN=.*#LIVE_INTERNAL_EVENTS_TOKEN=$live_internal_events_token#" .env
  else
    printf '\nLIVE_INTERNAL_EVENTS_TOKEN=%s\n' "$live_internal_events_token" >> .env
  fi
  chmod 600 .env
  echo "CREATED_LIVE_INTERNAL_EVENTS_TOKEN=true"
fi

signaling_replicas="$(sed -n 's/^SIGNALING_REPLICAS=//p' .env 2>/dev/null | tail -n 1 | tr -d '\r\"' || true)"
signaling_replicas="${signaling_replicas:-1}"
if ! [[ "$signaling_replicas" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: SIGNALING_REPLICAS debe ser un entero positivo" >&2
  exit 1
fi
multi_replica_approved="$(sed -n 's/^SIGNALING_MULTI_REPLICA_APPROVED=//p' .env 2>/dev/null | tail -n 1 | tr -d '\r\"' | tr '[:upper:]' '[:lower:]' || true)"
if [ "$signaling_replicas" -gt 1 ] && [ "$multi_replica_approved" != "true" ]; then
  echo "ERROR: signaling multi-replica sigue bloqueado hasta aprobar failover/rehidratacion de Live" >&2
  echo "Mantén SIGNALING_REPLICAS=1; no uses el flag de aprobación sin evidencia de la prueba operativa." >&2
  exit 1
fi

public_base_url="$(sed -n 's/^PUBLIC_BASE_URL=//p' .env 2>/dev/null | tail -n 1 | tr -d '\r\"' || true)"
allow_ephemeral_public_origin="${ALLOW_EPHEMERAL_PUBLIC_ORIGIN_OVERRIDE:-$(sed -n 's/^ALLOW_EPHEMERAL_PUBLIC_ORIGIN=//p' .env 2>/dev/null | tail -n 1 | tr -d '\r\"' | tr '[:upper:]' '[:lower:]' || true)}"
allow_ephemeral_public_origin="${allow_ephemeral_public_origin:-false}"
public_base_url_lower="$(printf '%s' "$public_base_url" | tr '[:upper:]' '[:lower:]')"
if [ "$allow_ephemeral_public_origin" != "true" ]; then
  case "$public_base_url_lower" in
    https://*.trycloudflare.com|https://*.trycloudflare.com/*|https://*.ngrok-free.app|https://*.ngrok-free.app/*|https://*.ngrok.io|https://*.ngrok.io/*|http://*|https://localhost*|https://127.0.0.1*)
      echo "ERROR: PUBLIC_BASE_URL debe ser un dominio HTTPS estable; los túneles efímeros sólo se permiten con ALLOW_EPHEMERAL_PUBLIC_ORIGIN=true durante pruebas controladas." >&2
      exit 1
      ;;
    '')
      echo "ERROR: PUBLIC_BASE_URL es obligatorio para desplegar producción; configura un dominio HTTPS estable antes de actualizar servicios." >&2
      exit 1
      ;;
  esac
fi

wait_service() {
  local service="$1"
  local timeout_seconds="${2:-180}"
  local deadline=$((SECONDS + timeout_seconds))
  local container_id state health
  local -a container_ids=()
  local all_ready

  while [ "$SECONDS" -lt "$deadline" ]; do
    mapfile -t container_ids < <(docker compose ps -q "$service" 2>/dev/null || true)
    if [ "${#container_ids[@]}" -gt 0 ]; then
      all_ready=true
      for container_id in "${container_ids[@]}"; do
        state="$(docker inspect --format '{{.State.Status}}' "$container_id" 2>/dev/null || true)"
        health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id" 2>/dev/null || true)"
        if [ "$state" != "running" ] || { [ "$health" != "healthy" ] && [ "$health" != "none" ]; }; then
          all_ready=false
          break
        fi
      done
      if [ "$all_ready" = true ]; then
        echo "SERVICE_READY=$service replicas=${#container_ids[@]}"
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

verify_edge_security_contract() {
  local edge_config
  edge_config="$(docker compose exec -T edge nginx -T 2>&1)" || {
    echo "ERROR: no se pudo leer la configuración Nginx desplegada" >&2
    return 1
  }
  local required_fragment
  for required_fragment in \
    'log_format novvin_safe' \
    'access_log /var/log/nginx/access.log novvin_safe' \
    'proxy_request_buffering off' \
    'proxy_buffering off' \
    'proxy_socket_keepalive on' \
    'location ^~ /storage/v1/upload/resumable' \
    'location = /v1/storage/upload' \
    'location ^~ /_novvin_storage/' \
    'internal;'; do
    if ! grep -Fq "$required_fragment" <<< "$edge_config"; then
      echo "ERROR: el edge desplegado no cumple el contrato de seguridad/rendimiento: $required_fragment" >&2
      return 1
    fi
  done
}

echo "==> Validando configuracion Docker Compose"
docker compose config --quiet

if [ "$SKIP_BUILD" = false ]; then
  echo "==> Construyendo imagenes sin interrumpir los contenedores actuales"
  docker compose build api ai-chatbot signaling realtime-gateway
fi

echo "==> Verificando dependencias de estado antes de actualizar"
docker compose up -d db
# File-backed Compose secrets preserve mount metadata at container creation.
# Recreate Redis so a rotated password or corrected host permissions cannot be
# hidden behind an old bind mount.
docker compose up -d --force-recreate signaling-redis
wait_service db 120
wait_service signaling-redis 120

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
verify_edge_security_contract
docker compose exec -T api node - <<'NODE'
const required = [
  'anonymous_bottles_v1',
  'community_music_v1',
  'moment_music_v1',
  'moment_music_aac_lc_v1',
  'image_moments_exact_6s_v1',
  'video_sound_identity_v1',
  'video_feed_session_v1',
  'video_feed_live_v1',
  'message_markers_v1',
  'video_watch_later_v1',
  'video_collections_v1',
]
fetch('http://127.0.0.1:3001/ready')
  .then(async (response) => {
    if (!response.ok) throw new Error(`READY_HTTP_${response.status}`)
    const payload = await response.json()
    const advertised = new Set(Array.isArray(payload.capabilities) ? payload.capabilities : [])
    const missing = required.filter((capability) => !advertised.has(capability))
    if (!payload.ok || missing.length > 0) {
      throw new Error(`READY_CAPABILITIES_MISSING:${missing.join(',')}`)
    }
  })
  .catch((error) => {
    console.error(error.message)
    process.exit(1)
  })
NODE
docker compose exec -T ai-chatbot node -e \
  "fetch('http://127.0.0.1:3010/ready').then(r=>{if(!r.ok)process.exit(1);return r.json()}).then(v=>{if(!v.ok)process.exit(1)}).catch(()=>process.exit(1))"
docker compose exec -T db pg_isready -U conectate -d conectate_fallback >/dev/null
docker compose exec -T signaling-redis sh -ec \
  'REDISCLI_AUTH="$(cat /run/secrets/signaling_redis_password)" redis-cli ping | grep -qx PONG'

echo "==> Verificacion no destructiva de Social, Videos, perfiles y multimedia publica"
docker compose exec -T \
  -e SMOKE_API_BASE_URL="http://edge:8080" \
  -e SMOKE_REQUIRE_CONTENT="${DEPLOY_REQUIRE_SOCIAL_CONTENT:-true}" \
  api npm run verify:social-read-paths

echo "==> Verificacion no destructiva de Mensajes, grupos, comunidades y avatares"
docker compose exec -T \
  -e SMOKE_API_BASE_URL="http://edge:8080" \
  -e SMOKE_REQUIRE_CONTENT="${DEPLOY_REQUIRE_MESSAGING_CONTENT:-true}" \
  api npm run verify:messaging-read-paths

echo "==> Verificacion no destructiva de roles y capacidades administrativas"
docker compose exec -T \
  -e E2E_API_URL="http://edge:8080" \
  api npm run verify:admin-panel-e2e

echo "STACK_DEPLOY_OK=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
