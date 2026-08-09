#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
LOCK_FILE="${XDG_RUNTIME_DIR:-/tmp}/conectate-playmatch-catalog-sync.lock"
PROVIDER="${PLAYMATCH_CATALOG_PROVIDER:-wikidata}"
PAGE_SIZE="${PLAYMATCH_CATALOG_PAGE_SIZE:-100}"
MAX_PAGES="${PLAYMATCH_CATALOG_MAX_PAGES:-3}"

if [ ! -s "$ENV_FILE" ]; then
  echo "PLAYMATCH_SYNC_ENV_MISSING" >&2
  exit 1
fi

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "PLAYMATCH_SYNC_ALREADY_RUNNING"
  exit 0
fi

api_image="$(docker inspect conectate-local-fallback-api-main --format '{{.Image}}' 2>/dev/null || true)"
if [ -z "$api_image" ]; then
  echo "PLAYMATCH_SYNC_API_IMAGE_MISSING" >&2
  exit 1
fi

# The password remains inside the container environment. It is never expanded
# into the host command line, stdout, stderr, or the repository.
timeout 20m docker run --rm \
  --network host \
  --env-file "$ENV_FILE" \
  -e "PLAYMATCH_SYNC_PROVIDER=$PROVIDER" \
  -e "PLAYMATCH_SYNC_PAGE_SIZE=$PAGE_SIZE" \
  -e "PLAYMATCH_SYNC_MAX_PAGES=$MAX_PAGES" \
  "$api_image" \
  sh -ec '
    export DATABASE_URL="postgres://conectate:${POSTGRES_PASSWORD}@127.0.0.1:${FALLBACK_DB_PORT:-5433}/conectate_fallback"
    exec node scripts/sync-playmatch-catalog.js \
      --provider="${PLAYMATCH_SYNC_PROVIDER}" \
      --page-size="${PLAYMATCH_SYNC_PAGE_SIZE}" \
      --max-pages="${PLAYMATCH_SYNC_MAX_PAGES}"
  '
