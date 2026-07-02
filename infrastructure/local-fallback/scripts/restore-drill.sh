#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${1:-${BACKUP_ROOT:-$HOME/conectate-backups}/latest}"
DRILL_DB="conectate_restore_drill_$(date -u +%Y%m%d%H%M%S)"

BACKUP_DIR="$(readlink -f "$BACKUP_DIR")"
test -s "$BACKUP_DIR/postgres.dump"
cd "$ROOT_DIR"

cleanup() {
  docker compose exec -T db dropdb -U conectate --if-exists "$DRILL_DB" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> Creando base aislada $DRILL_DB"
docker compose exec -T db createdb -U conectate "$DRILL_DB"
docker compose exec -T db pg_restore \
  -U conectate -d "$DRILL_DB" --no-owner --no-privileges \
  < "$BACKUP_DIR/postgres.dump"

table_count="$(docker compose exec -T db psql -U conectate -d "$DRILL_DB" -Atc \
  "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema');" | tr -d '[:space:]')"
if ! [[ "$table_count" =~ ^[0-9]+$ ]] || [ "$table_count" -lt 1 ]; then
  echo "ERROR: la restauracion no contiene tablas de aplicacion"
  exit 1
fi

echo "RESTORE_DRILL_OK=$BACKUP_DIR"
echo "RESTORED_TABLES=$table_count"
