#!/usr/bin/env bash
set -Eeuo pipefail

# Creates one encrypted, self-contained migration bundle with the database,
# uploads, runtime source, .env and Firebase service account. Run it on the
# current Ubuntu server as the normal operational user.

umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${MIGRATION_OUTPUT_DIR:-$HOME/conectate-migrations}"
BACKUP_ROOT="${BACKUP_ROOT:-$HOME/conectate-backups}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BUNDLE_NAME="conectate-mini-pc-$STAMP"
WORK_DIR="$(mktemp -d)"
PAYLOAD_DIR="$WORK_DIR/$BUNDLE_NAME"
PLAIN_ARCHIVE="$WORK_DIR/$BUNDLE_NAME.tar.gz"
FINAL_ARCHIVE="$OUTPUT_DIR/$BUNDLE_NAME.tar.gz.enc"

cleanup() {
  rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT

read_passphrase() {
  if [ -n "${MIGRATION_PASSPHRASE:-}" ]; then
    printf '%s' "$MIGRATION_PASSPHRASE"
    return
  fi
  if [ ! -t 0 ]; then
    echo "ERROR: define MIGRATION_PASSPHRASE para una ejecución no interactiva." >&2
    exit 2
  fi
  local first second
  read -r -s -p "Contraseña para cifrar la migración: " first
  echo >&2
  read -r -s -p "Repite la contraseña: " second
  echo >&2
  if [ -z "$first" ] || [ "$first" != "$second" ]; then
    echo "ERROR: las contraseñas no coinciden o están vacías." >&2
    exit 2
  fi
  printf '%s' "$first"
}

test -s "$ROOT_DIR/.env"
test -s "$ROOT_DIR/secrets/firebase-service-account.json"
command -v docker >/dev/null
command -v openssl >/dev/null
command -v tar >/dev/null

cd "$ROOT_DIR"
docker compose config --quiet
docker compose exec -T db pg_isready -U conectate -d conectate_fallback >/dev/null
curl -fsS http://127.0.0.1:${FALLBACK_API_PORT:-3001}/ready \
  | grep -q '"configured":true'

echo "==> Creando respaldo fresco y verificándolo"
if [ "${MIGRATION_USE_LATEST_BACKUP:-false}" != "true" ]; then
  BACKUP_ROOT="$BACKUP_ROOT" bash "$ROOT_DIR/scripts/backup-ubuntu-primary.sh"
else
  echo "==> Modo de ensayo: reutilizando el último respaldo verificado"
fi
BACKUP_DIR="$(readlink -f "$BACKUP_ROOT/latest")"
bash "$ROOT_DIR/scripts/verify-backup.sh" "$BACKUP_DIR"

mkdir -p "$PAYLOAD_DIR/secrets"
cp "$BACKUP_DIR/postgres.dump" "$PAYLOAD_DIR/postgres.dump"
cp "$BACKUP_DIR/storage.tar.gz" "$PAYLOAD_DIR/storage.tar.gz"
cp "$ROOT_DIR/.env" "$PAYLOAD_DIR/runtime.env"
cp "$ROOT_DIR/secrets/firebase-service-account.json" \
  "$PAYLOAD_DIR/secrets/firebase-service-account.json"
cp "$ROOT_DIR/scripts/import-mini-pc-migration.sh" "$PAYLOAD_DIR/import-mini-pc-migration.sh"

signaling_context="$(sed -n 's/^SIGNALING_CONTEXT=//p' "$ROOT_DIR/.env" | tail -n 1)"
signaling_context="${signaling_context:-../signaling-server}"
if [[ "$signaling_context" = /* ]]; then
  SIGNALING_DIR="$signaling_context"
else
  SIGNALING_DIR="$(readlink -f "$ROOT_DIR/$signaling_context")"
fi
test -d "$SIGNALING_DIR"

echo "==> Empaquetando código reproducible"
tar -C "$ROOT_DIR" \
  --exclude='./.env' \
  --exclude='./.git' \
  --exclude='./secrets' \
  --exclude='./backups' \
  --exclude='./logs' \
  --exclude='./api/storage-data' \
  --exclude='./imports/*.csv' \
  --exclude='./imports/*.json' \
  --exclude='./node_modules' \
  --exclude='./api/node_modules' \
  --exclude='./realtime-gateway/node_modules' \
  -czf "$PAYLOAD_DIR/local-fallback-source.tar.gz" .
tar -C "$SIGNALING_DIR" \
  --exclude='./node_modules' \
  --exclude='./.git' \
  -czf "$PAYLOAD_DIR/signaling-source.tar.gz" .

db_bytes="$(stat -c %s "$PAYLOAD_DIR/postgres.dump")"
storage_bytes="$(stat -c %s "$PAYLOAD_DIR/storage.tar.gz")"
db_tables="$(docker compose exec -T db psql -U conectate -d conectate_fallback -Atc \
  "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema');" \
  | tr -d '[:space:]')"
fcm_tokens="$(docker compose exec -T db psql -U conectate -d conectate_fallback -Atc \
  'SELECT count(*) FROM fallback_fcm_tokens;' | tr -d '[:space:]')"

cat > "$PAYLOAD_DIR/metadata.json" <<EOF
{
  "schemaVersion": 1,
  "createdAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "sourceHost": "$(hostname)",
  "database": "conectate_fallback",
  "databaseBytes": $db_bytes,
  "storageBytes": $storage_bytes,
  "databaseTables": $db_tables,
  "fcmTokens": $fcm_tokens,
  "firebaseServiceAccountIncluded": true,
  "verification": "passed"
}
EOF

(
  cd "$PAYLOAD_DIR"
  sha256sum \
    postgres.dump \
    storage.tar.gz \
    runtime.env \
    secrets/firebase-service-account.json \
    local-fallback-source.tar.gz \
    signaling-source.tar.gz \
    import-mini-pc-migration.sh \
    metadata.json > SHA256SUMS
)

tar -C "$WORK_DIR" -czf "$PLAIN_ARCHIVE" "$BUNDLE_NAME"
passphrase="$(read_passphrase)"
mkdir -p "$OUTPUT_DIR"
MIGRATION_BUNDLE_PASSPHRASE="$passphrase" \
  openssl enc -aes-256-cbc -salt -pbkdf2 -iter 250000 \
    -pass env:MIGRATION_BUNDLE_PASSPHRASE \
    -in "$PLAIN_ARCHIVE" -out "$FINAL_ARCHIVE"
chmod 600 "$FINAL_ARCHIVE"

echo "MIGRATION_BUNDLE_OK=$FINAL_ARCHIVE"
echo "MIGRATION_BUNDLE_BYTES=$(stat -c %s "$FINAL_ARCHIVE")"
echo "DATABASE_TABLES=$db_tables"
echo "FCM_TOKENS=$fcm_tokens"
echo "IMPORT_SCRIPT=$ROOT_DIR/scripts/import-mini-pc-migration.sh"
