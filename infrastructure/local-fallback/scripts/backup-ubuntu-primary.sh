#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_ROOT="${BACKUP_ROOT:-$HOME/conectate-backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FINAL_DIR="$BACKUP_ROOT/$STAMP"
TEMP_DIR="$BACKUP_ROOT/.partial-$STAMP-$$"

mkdir -p "$BACKUP_ROOT"
exec 9>"$BACKUP_ROOT/.backup.lock"
if ! flock -n 9; then
  echo "Otra copia de seguridad sigue en ejecucion."
  exit 0
fi

cleanup() {
  rm -rf -- "$TEMP_DIR"
}
trap cleanup EXIT

cd "$ROOT_DIR"
mkdir -p "$TEMP_DIR"

echo "==> Verificando servicios"
docker compose exec -T db pg_isready -U conectate -d conectate_fallback >/dev/null
docker compose exec -T api sh -lc 'test -r /data/storage'

echo "==> Exportando PostgreSQL"
docker compose exec -T db pg_dump \
  -U conectate -d conectate_fallback \
  --format=custom --compress=6 --no-owner --no-privileges \
  > "$TEMP_DIR/postgres.dump"

echo "==> Exportando storage local"
docker compose exec -T -u 10001:10001 api tar -C /data/storage -czf - . \
  > "$TEMP_DIR/storage.tar.gz"

echo "==> Exportando estado local de Novvin"
docker compose exec -T -u 1000:1000 ai-chatbot tar -C /data/ai -czf - . \
  > "$TEMP_DIR/ai-chatbot-data.tar.gz"

cp docker-compose.yml "$TEMP_DIR/docker-compose.yml"

echo "==> Verificando artefactos"
test -s "$TEMP_DIR/postgres.dump"
test -s "$TEMP_DIR/storage.tar.gz"
test -s "$TEMP_DIR/ai-chatbot-data.tar.gz"
docker compose exec -T db pg_restore --list < "$TEMP_DIR/postgres.dump" >/dev/null
tar -tzf "$TEMP_DIR/storage.tar.gz" >/dev/null
tar -tzf "$TEMP_DIR/ai-chatbot-data.tar.gz" >/dev/null

(
  cd "$TEMP_DIR"
  sha256sum postgres.dump storage.tar.gz ai-chatbot-data.tar.gz docker-compose.yml > SHA256SUMS
)

db_bytes="$(stat -c %s "$TEMP_DIR/postgres.dump")"
storage_bytes="$(stat -c %s "$TEMP_DIR/storage.tar.gz")"
ai_data_bytes="$(stat -c %s "$TEMP_DIR/ai-chatbot-data.tar.gz")"
cat > "$TEMP_DIR/metadata.json" <<EOF
{
  "schemaVersion": 2,
  "createdAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "database": "conectate_fallback",
  "databaseBytes": $db_bytes,
  "storageBytes": $storage_bytes,
  "aiDataBytes": $ai_data_bytes,
  "verification": "passed"
}
EOF

mv "$TEMP_DIR" "$FINAL_DIR"
trap - EXIT
ln -sfn "$FINAL_DIR" "$BACKUP_ROOT/latest"

if [[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]] && [ "$RETENTION_DAYS" -gt 0 ]; then
  find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d \
    -name '20??????T??????Z' -mtime "+$RETENTION_DAYS" -exec rm -rf -- {} +
fi

echo "BACKUP_OK=$FINAL_DIR"
echo "POSTGRES_BYTES=$db_bytes"
echo "STORAGE_BYTES=$storage_bytes"
echo "AI_DATA_BYTES=$ai_data_bytes"
