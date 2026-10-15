#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="${1:-${BACKUP_ROOT:-$HOME/conectate-backups}/latest}"

BACKUP_DIR="$(readlink -f "$BACKUP_DIR")"
test -d "$BACKUP_DIR"
test -s "$BACKUP_DIR/postgres.dump"
test -s "$BACKUP_DIR/storage.tar.gz"
test -s "$BACKUP_DIR/SHA256SUMS"

cd "$BACKUP_DIR"
# Never let a tampered checksum manifest read an arbitrary host file.
declare -A checksum_files=()
while read -r digest artifact extra; do
  [[ "$digest" =~ ^[a-f0-9]{64}$ && -z "${extra:-}" ]] || { echo "ERROR: invalid checksum entry" >&2; exit 1; }
  case "$artifact" in postgres.dump|roles-no-passwords.sql|storage.tar.gz|ai-chatbot-data.tar.gz|docker-compose.yml) ;;
    *) echo "ERROR: unexpected checksum artifact" >&2; exit 1 ;;
  esac
  [[ -z "${checksum_files[$artifact]:-}" ]] || { echo "ERROR: duplicate checksum artifact" >&2; exit 1; }
  test -f "$artifact" && test ! -L "$artifact"
  checksum_files[$artifact]=1
done < SHA256SUMS
test "${checksum_files[postgres.dump]:-}" = 1
test "${checksum_files[storage.tar.gz]:-}" = 1
if test -f metadata.json && grep -Eq '"schemaVersion"[[:space:]]*:[[:space:]]*3([[:space:]]*,|[[:space:]]*$)' metadata.json; then
  test -s roles-no-passwords.sql
  test "${checksum_files[roles-no-passwords.sql]:-}" = 1
  test "${checksum_files[ai-chatbot-data.tar.gz]:-}" = 1
  test "${checksum_files[docker-compose.yml]:-}" = 1
  if grep -Eiq '^[[:space:]]*ALTER[[:space:]]+ROLE.*[[:space:]]PASSWORD[[:space:]]' roles-no-passwords.sql; then
    echo "ERROR: role backup contains password assignments" >&2; exit 1
  fi
else
  echo "WARN: copia heredada sin contrato de preservacion de roles y permisos"
fi
sha256sum --check SHA256SUMS
tar -tzf storage.tar.gz >/dev/null
if [ -s ai-chatbot-data.tar.gz ]; then
  tar -tzf ai-chatbot-data.tar.gz >/dev/null
else
  echo "WARN: copia heredada sin estado de Novvin"
fi
cd "$ROOT_DIR"
docker compose exec -T db pg_restore --list < "$BACKUP_DIR/postgres.dump" >/dev/null

echo "BACKUP_VERIFIED=$BACKUP_DIR"

echo "SCOPE=artifact integrity only; owner/ACL/role restoration requires an isolated full policy rehearsal"
