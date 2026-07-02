#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${1:-${BACKUP_ROOT:-$HOME/conectate-backups}/latest}"

BACKUP_DIR="$(readlink -f "$BACKUP_DIR")"
test -d "$BACKUP_DIR"
test -s "$BACKUP_DIR/postgres.dump"
test -s "$BACKUP_DIR/storage.tar.gz"
test -s "$BACKUP_DIR/SHA256SUMS"

cd "$BACKUP_DIR"
sha256sum --check SHA256SUMS
tar -tzf storage.tar.gz >/dev/null
cd "$ROOT_DIR"
docker compose exec -T db pg_restore --list < "$BACKUP_DIR/postgres.dump" >/dev/null

echo "BACKUP_VERIFIED=$BACKUP_DIR"
