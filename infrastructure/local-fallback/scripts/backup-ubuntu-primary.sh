#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_ROOT="${BACKUP_ROOT:-$HOME/conectate-backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FINAL_DIR="$BACKUP_ROOT/$STAMP"
TEMP_DIR="$BACKUP_ROOT/.partial-$STAMP-$$"
TEMP_CREATED=0
LATEST_TEMP_CREATED=0
LATEST_TEMP=""

# Use a dedicated absolute backup directory, including for cleanup/retention.
[[ "$BACKUP_ROOT" = /* ]] || { echo "ERROR: BACKUP_ROOT must be absolute" >&2; exit 1; }
case "$BACKUP_ROOT" in /|/home|/var|/tmp|/usr|/etc|"$HOME")
  echo "ERROR: BACKUP_ROOT must be a dedicated directory" >&2; exit 1 ;;
esac
mkdir -p -- "$BACKUP_ROOT"
BACKUP_ROOT="$(readlink -f -- "$BACKUP_ROOT")"
case "$BACKUP_ROOT" in /|/home|/var|/tmp|/usr|/etc|"$HOME")
  echo "ERROR: resolved BACKUP_ROOT is not dedicated" >&2; exit 1 ;;
esac
chmod 700 -- "$BACKUP_ROOT"
FINAL_DIR="$BACKUP_ROOT/$STAMP"
TEMP_DIR="$BACKUP_ROOT/.partial-$STAMP-$$"
exec 9>"$BACKUP_ROOT/.backup.lock"
if ! flock -n 9; then
  echo "Otra copia de seguridad sigue en ejecucion."
  exit 0
fi

# A malformed latest must not redirect publication or create a link inside a
# directory. Only a dated direct-child backup directory can be its target.
LATEST_LINK="$BACKUP_ROOT/latest"
if [ -L "$LATEST_LINK" ]; then
  LATEST_TARGET="$(readlink -f -- "$LATEST_LINK")" || { echo "ERROR: unresolved latest backup link" >&2; exit 1; }
  if [[ "$(dirname -- "$LATEST_TARGET")" != "$BACKUP_ROOT" || ! "$(basename -- "$LATEST_TARGET")" =~ ^20[0-9]{6}T[0-9]{6}Z$ || ! -d "$LATEST_TARGET" ]]; then
    echo "ERROR: latest backup link must target a dated directory inside BACKUP_ROOT" >&2
    exit 1
  fi
elif [ -e "$LATEST_LINK" ]; then
  echo "ERROR: latest backup entry must be a symbolic link" >&2
  exit 1
fi

cleanup() {
  if [ "$LATEST_TEMP_CREATED" = 1 ]; then
    if [[ "$LATEST_TEMP" = "$BACKUP_ROOT/.latest-"* && -L "$LATEST_TEMP" ]]; then
      rm -- "$LATEST_TEMP"
    else
      echo "ERROR: refusing cleanup of an unexpected latest entry" >&2
      return 1
    fi
  fi
  [ "$TEMP_CREATED" = 1 ] || return 0
  if [[ "$TEMP_DIR" = "$BACKUP_ROOT/.partial-"* && "$TEMP_DIR" != "$BACKUP_ROOT" ]]; then
    rm -rf -- "$TEMP_DIR"
  else
    echo "ERROR: refusing cleanup outside the dedicated backup directory" >&2
    return 1
  fi
}
trap cleanup EXIT

cd "$ROOT_DIR"
test ! -e "$FINAL_DIR"
test ! -e "$TEMP_DIR"
mkdir -m 700 -- "$TEMP_DIR"
TEMP_CREATED=1

echo "==> Verificando servicios"
docker compose exec -T db pg_isready -U conectate -d conectate_fallback >/dev/null
docker compose exec -T api sh -lc 'test -r /data/storage'

echo "==> Exportando PostgreSQL"
docker compose exec -T db pg_dump \
  -U conectate -d conectate_fallback \
  --format=custom --compress=6 \
  > "$TEMP_DIR/postgres.dump"

# pg_dump does not include cluster roles. Store role flags/memberships without
# authentication hashes; recovered login passwords must be provisioned separately.
echo "==> Exportando roles sin contrasenas"
docker compose exec -T db pg_dumpall -U conectate --roles-only --no-role-passwords \
  > "$TEMP_DIR/roles-no-passwords.sql"
if grep -Eiq '^[[:space:]]*ALTER[[:space:]]+ROLE.*[[:space:]]PASSWORD[[:space:]]' "$TEMP_DIR/roles-no-passwords.sql"; then
  echo "ERROR: role backup unexpectedly contains password assignments" >&2
  exit 1
fi

echo "==> Exportando storage local"
bash "$ROOT_DIR/scripts/archive-protected-storage.sh" \
  > "$TEMP_DIR/storage.tar.gz"

echo "==> Exportando estado local de Novvin"
docker compose exec -T -u 1000:1000 ai-chatbot tar -C /data/ai -czf - . \
  > "$TEMP_DIR/ai-chatbot-data.tar.gz"

cp docker-compose.yml "$TEMP_DIR/docker-compose.yml"

echo "==> Verificando artefactos"
test -s "$TEMP_DIR/postgres.dump"
test -s "$TEMP_DIR/roles-no-passwords.sql"
test -s "$TEMP_DIR/storage.tar.gz"
test -s "$TEMP_DIR/ai-chatbot-data.tar.gz"
docker compose exec -T db pg_restore --list < "$TEMP_DIR/postgres.dump" >/dev/null
tar -tzf "$TEMP_DIR/storage.tar.gz" >/dev/null
tar -tzf "$TEMP_DIR/ai-chatbot-data.tar.gz" >/dev/null

(
  cd "$TEMP_DIR"
  sha256sum postgres.dump roles-no-passwords.sql storage.tar.gz ai-chatbot-data.tar.gz docker-compose.yml > SHA256SUMS
)

db_bytes="$(stat -c %s "$TEMP_DIR/postgres.dump")"
roles_bytes="$(stat -c %s "$TEMP_DIR/roles-no-passwords.sql")"
storage_bytes="$(stat -c %s "$TEMP_DIR/storage.tar.gz")"
ai_data_bytes="$(stat -c %s "$TEMP_DIR/ai-chatbot-data.tar.gz")"
cat > "$TEMP_DIR/metadata.json" <<EOF
{
  "schemaVersion": 3,
  "createdAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "database": "conectate_fallback",
  "databaseBytes": $db_bytes,
  "rolesBytes": $roles_bytes,
  "databaseAclIncluded": true,
  "restoreOwnersRequired": true,
  "rolePasswordsIncluded": false,
  "storageBytes": $storage_bytes,
  "aiDataBytes": $ai_data_bytes,
  "verification": "passed"
}
EOF

LATEST_TEMP="$BACKUP_ROOT/.latest-$STAMP-$$"
test ! -e "$LATEST_TEMP" && test ! -L "$LATEST_TEMP"
ln -s -- "$FINAL_DIR" "$LATEST_TEMP"
LATEST_TEMP_CREATED=1
mv -- "$TEMP_DIR" "$FINAL_DIR"
# -T prevents a replaced directory from silently becoming a container for the
# link. Both entries live on the same filesystem, so renaming is atomic.
mv -Tf -- "$LATEST_TEMP" "$LATEST_LINK"
LATEST_TEMP_CREATED=0
trap - EXIT

if [[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]] && [ "$RETENTION_DAYS" -gt 0 ]; then
  find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d \
    -name '20??????T??????Z' -mtime "+$RETENTION_DAYS" -exec rm -rf -- {} +
fi

echo "BACKUP_OK=$FINAL_DIR"
echo "POSTGRES_BYTES=$db_bytes"
echo "ROLES_BYTES=$roles_bytes"
echo "STORAGE_BYTES=$storage_bytes"
echo "AI_DATA_BYTES=$ai_data_bytes"
