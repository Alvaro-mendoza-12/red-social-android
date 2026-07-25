#!/usr/bin/env bash
set -Eeuo pipefail

STAGE_DIR="${1:?stage directory is required}"
ROOT_DIR="${2:-$HOME/conectate-local-fallback}"
RELEASE="${3:?release name is required}"
RELEASE_DIR="$HOME/conectate-releases/$RELEASE"
BACKUP_ARCHIVE="$RELEASE_DIR/predeploy-files.tar.gz"
COMPOSE_LOCK="${CONECTATE_COMPOSE_LOCK:-$HOME/.cache/conectate-compose.lock}"

if [[ "$STAGE_DIR" != /tmp/api-image-admin-* ]]; then
  echo "ERROR: invalid stage directory: $STAGE_DIR" >&2
  exit 2
fi
test -d "$ROOT_DIR"
test -s "$BACKUP_ARCHIVE"

FILES=(
  api/src/server.js
  api/src/data-routes.js
  api/src/admin-moderation-routes.js
  api/src/storage-routes.js
  api/package.json
  api/package-lock.json
  nginx/default.conf
)
for file in "${FILES[@]}"; do
  test -s "$STAGE_DIR/$file"
done

mkdir -p "$(dirname "$COMPOSE_LOCK")"
exec 9>"$COMPOSE_LOCK"
flock --wait 600 9

wait_service() {
  local service="$1"
  local timeout_seconds="${2:-240}"
  local deadline=$((SECONDS + timeout_seconds))
  local container_id state health
  while (( SECONDS < deadline )); do
    container_id="$(docker compose ps -q "$service" 2>/dev/null || true)"
    if [[ -n "$container_id" ]]; then
      state="$(docker inspect --format '{{.State.Status}}' "$container_id" 2>/dev/null || true)"
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id" 2>/dev/null || true)"
      if [[ "$state" == running && ( "$health" == healthy || "$health" == none ) ]]; then
        echo "SERVICE_READY=$service state=$state health=$health"
        return 0
      fi
    fi
    sleep 5
  done
  docker compose ps "$service" >&2 || true
  docker compose logs --tail=160 "$service" >&2 || true
  return 1
}

rollback_required=false
rollback() {
  local exit_code=$?
  trap - ERR
  if [[ "$rollback_required" == true ]]; then
    echo "==> Fallo detectado; restaurando release anterior" >&2
    tar -xzf "$BACKUP_ARCHIVE" -C "$ROOT_DIR"
    cd "$ROOT_DIR"
    docker compose build api >&2 || true
    docker compose up -d --no-deps --force-recreate api >&2 || true
    wait_service api 240 >&2 || true
    docker compose up -d --no-deps --force-recreate edge >&2 || true
    wait_service edge 180 >&2 || true
  fi
  exit "$exit_code"
}
trap rollback ERR

echo "==> Validando JavaScript preparado"
docker run --rm \
  -v "$STAGE_DIR/api:/usr/src/app:ro" \
  -w /usr/src/app \
  node:22-alpine \
  sh -lc 'node --check src/server.js && node --check src/data-routes.js && node --check src/admin-moderation-routes.js && node --check src/storage-routes.js'

echo "==> Instalando archivos dirigidos"
rollback_required=true
for file in "${FILES[@]}"; do
  install -D -m 664 "$STAGE_DIR/$file" "$ROOT_DIR/$file"
done

cd "$ROOT_DIR"
docker compose config --quiet
docker compose exec -T edge nginx -t

echo "==> Reconstruyendo API"
docker compose build api
docker compose up -d --no-deps --force-recreate api
wait_service api 300

ready_json="$(curl -fsS --max-time 15 http://127.0.0.1:3001/ready)"
for capability in admin_users_v1 hard_delete_suspended_users_v1 image_variants_v1 profile_ranking_v1; do
  grep -q "\"$capability\"" <<<"$ready_json"
done

echo "==> Recargando Nginx"
docker compose up -d --no-deps --force-recreate edge
wait_service edge 180
edge_ready_json="$(curl -fsS --max-time 15 http://127.0.0.1:3080/ready)"
for capability in admin_users_v1 hard_delete_suspended_users_v1 image_variants_v1 profile_ranking_v1; do
  grep -q "\"$capability\"" <<<"$edge_ready_json"
done

cat > "$ROOT_DIR/.conectate-release" <<EOF
release=$RELEASE
deployed_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
source=deploy-targeted-api-release.sh
host=$(hostname)
EOF

rollback_required=false
rm -rf -- "$STAGE_DIR"
echo "TARGETED_DEPLOY_OK=$RELEASE"
