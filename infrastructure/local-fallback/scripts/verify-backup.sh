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
test ! -L "$BACKUP_DIR/SHA256SUMS"

cd "$BACKUP_DIR"
# Never let a tampered checksum manifest read an arbitrary host file.
declare -A checksum_files=()
while read -r digest artifact extra || [[ -n "$digest$artifact$extra" ]]; do
  [[ "$digest" =~ ^[a-f0-9]{64}$ && -z "${extra:-}" ]] || { echo "ERROR: invalid checksum entry" >&2; exit 1; }
  case "$artifact" in postgres.dump|roles-no-passwords.sql|storage.tar.gz|ai-chatbot-data.tar.gz|docker-compose.yml) ;;
    *) echo "ERROR: unexpected checksum artifact" >&2; exit 1 ;;
  esac
  [[ -z "${checksum_files[$artifact]:-}" ]] || { echo "ERROR: duplicate checksum artifact" >&2; exit 1; }
  [[ -f "$artifact" && ! -L "$artifact" ]] || { echo "ERROR: invalid backup file: $artifact" >&2; exit 1; }
  checksum_files[$artifact]=1
done < SHA256SUMS
version=0
if [[ -e metadata.json || -L metadata.json ]]; then
  [[ -f metadata.json && ! -L metadata.json ]] || { echo "ERROR: invalid metadata.json" >&2; exit 1; }
  version="$(python3 - <<'PY'
import json
import sys
from pathlib import Path

try:
    metadata = json.loads(Path("metadata.json").read_text(encoding="utf-8"))
    version = metadata["schemaVersion"]
    if type(version) is not int or version not in (1, 2, 3):
        raise ValueError("unsupported backup schemaVersion")
    sizes = {"databaseBytes": "postgres.dump", "storageBytes": "storage.tar.gz"}
    if version >= 2:
        sizes["aiDataBytes"] = "ai-chatbot-data.tar.gz"
    if version >= 3:
        sizes["rolesBytes"] = "roles-no-passwords.sql"
    for field, name in sizes.items():
        size = metadata[field]
        path = Path(name)
        if type(size) is not int or size <= 0 or path.is_symlink() or path.stat().st_size != size:
            raise ValueError(f"size mismatch for {name}")
except (OSError, ValueError, KeyError, TypeError) as error:
    sys.exit(f"ERROR: invalid backup metadata: {error}")
print(version)
PY
  )"
else
  echo "WARN: backup has no metadata; declared sizes cannot be checked" >&2
  if [[ -e roles-no-passwords.sql || -L roles-no-passwords.sql ]]; then
    version=3
  elif [[ -e ai-chatbot-data.tar.gz || -L ai-chatbot-data.tar.gz ]]; then
    version=2
  fi
fi

required=(postgres.dump storage.tar.gz)
if (( version >= 1 )); then required+=(docker-compose.yml); fi
if (( version >= 2 )); then required+=(ai-chatbot-data.tar.gz); fi
if (( version >= 3 )); then required+=(roles-no-passwords.sql); fi
for artifact in "${required[@]}"; do
  [[ "${checksum_files[$artifact]:-}" = 1 && -s "$artifact" ]] || { echo "ERROR: missing required backup artifact: $artifact" >&2; exit 1; }
done
for artifact in docker-compose.yml ai-chatbot-data.tar.gz roles-no-passwords.sql; do
  if [[ -e "$artifact" || -L "$artifact" ]]; then
    [[ "${checksum_files[$artifact]:-}" = 1 ]] || { echo "ERROR: missing checksum for $artifact" >&2; exit 1; }
  fi
done
if [[ -f roles-no-passwords.sql ]]; then
  if grep -Eiq '^[[:space:]]*ALTER[[:space:]]+ROLE.*[[:space:]]PASSWORD[[:space:]]' roles-no-passwords.sql; then
    echo "ERROR: role backup contains password assignments" >&2; exit 1
  fi
else
  echo "WARN: copia heredada sin contrato de preservacion de roles y permisos"
fi
sha256sum --check --strict SHA256SUMS
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
