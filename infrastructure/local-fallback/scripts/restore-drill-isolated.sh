#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# No production DB connection, network, host port, writable backup, or durable volume.
# Usage: bash scripts/restore-drill-isolated.sh [--with-roles] /absolute/path/to/verified/backup [postgres-image]
WITH_ROLES=0
TARGET_DB=novvin_restore_drill
INITIAL_DB="$TARGET_DB"
if [[ "${1:-}" == --with-roles ]]; then
  WITH_ROLES=1
  TARGET_DB=conectate_fallback
  INITIAL_DB=postgres
  shift
fi
BACKUP_DIR="$(readlink -f "${1:?Provide the directory of a verified backup}")"
test -d "$BACKUP_DIR"
test -s "$BACKUP_DIR/postgres.dump"
if (( WITH_ROLES )); then
  test -s "$BACKUP_DIR/roles-no-passwords.sql" || { echo "ERROR: role archive required for this drill" >&2; exit 1; }
  bash "$(dirname "${BASH_SOURCE[0]}")/verify-backup.sh" --files-only "$BACKUP_DIR"
fi
IMAGE="$(docker image inspect --format '{{.Id}}' "${2:-novvin-postgres:16.16-alpine-r1}")"
DRILL_NAME="novvin-restore-check-$(date -u +%Y%m%d%H%M%S)-$$"
DRILL_LABEL="novvin.restore-check=$DRILL_NAME"
LOG_DIR="$(mktemp -d /tmp/novvin-restore-check.XXXXXXXX)"
chmod 700 "$LOG_DIR"
CREATED=0

cleanup() {
  local result=$?
  trap - EXIT
  if [ "$CREATED" = 1 ] && docker container inspect "$DRILL_NAME" >/dev/null 2>&1; then
    local owner
    owner="$(docker inspect --format '{{ index .Config.Labels "novvin.restore-check" }}' "$DRILL_NAME")"
    if [ "$owner" = "$DRILL_NAME" ]; then
      docker rm -f "$DRILL_NAME" >/dev/null
    else
      echo "ERROR: container ownership mismatch; no container removed" >&2
      result=1
    fi
  fi
  echo "RESTORE_DIAGNOSTIC_DIR=$LOG_DIR"
  exit "$result"
}
trap cleanup EXIT

# The installed Alpine image uses uid/gid 70; discover it instead of relying on that detail.
DB_UID="$(docker run --rm --network none --read-only --entrypoint id "$IMAGE" -u postgres)"
DB_GID="$(docker run --rm --network none --read-only --entrypoint id "$IMAGE" -g postgres)"
[[ "$DB_UID" =~ ^[0-9]+$ && "$DB_GID" =~ ^[0-9]+$ ]]
docker run --detach --rm --name "$DRILL_NAME" --label "$DRILL_LABEL" \
  --network none --read-only --user postgres --cap-drop ALL \
  --security-opt no-new-privileges --memory 1g --cpus 1 --pids-limit 128 \
  --tmpfs "/var/lib/postgresql/data:rw,noexec,nosuid,size=768m,mode=0700,uid=$DB_UID,gid=$DB_GID" \
  --tmpfs "/var/run/postgresql:rw,noexec,nosuid,size=16m,mode=0755,uid=$DB_UID,gid=$DB_GID" \
  --env POSTGRES_USER=conectate --env "POSTGRES_DB=$INITIAL_DB" \
  --env POSTGRES_HOST_AUTH_METHOD=trust \
  "$IMAGE" > "$LOG_DIR/container-id"
CREATED=1
READY=0
for attempt in $(seq 1 30); do
  # pg_isready also accepts the entrypoint's temporary bootstrap server. That
  # server shuts down before PID 1 becomes postgres, interrupting pg_restore.
  if docker exec "$DRILL_NAME" sh -c \
    '[ "$(cat /proc/1/comm)" = postgres ] && pg_isready -U conectate -d "$1"' sh "$INITIAL_DB" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 1
done
if [ "$READY" != 1 ]; then
  docker logs "$DRILL_NAME" > "$LOG_DIR/startup.log" 2>&1 || true
  echo "ERROR: isolated PostgreSQL did not start" >&2
  exit 1
fi

# The backup remains mode 0600 under the operator UID. Stream it through
# stdin rather than granting the isolated PostgreSQL UID access to the host file.
restore_options=(--dbname="$TARGET_DB" --no-owner --no-privileges)
if (( WITH_ROLES )); then
  # Membership grants can name the original bootstrap role as grantor. The
  # container already created that role. Keep all its ALTER and GRANT records.
  if ! awk '/^CREATE ROLE (conectate|"conectate");[[:space:]]*$/ { count++; next }
            { print }
            END { if (count != 1) exit 1 }' "$BACKUP_DIR/roles-no-passwords.sql" |
      docker exec -i "$DRILL_NAME" psql -X --single-transaction --set=ON_ERROR_STOP=1 \
        -U conectate --dbname=postgres -f - > "$LOG_DIR/roles.log" 2>&1; then
    echo "ERROR: isolated role restore failed; see the private diagnostic directory" >&2
    exit 1
  fi
  # Creating the archived database also restores its owner, grants and settings.
  restore_options=(--dbname=postgres --create)
fi
if ! docker exec -i "$DRILL_NAME" pg_restore -U conectate --exit-on-error "${restore_options[@]}" \
  < "$BACKUP_DIR/postgres.dump" > "$LOG_DIR/restore.log" 2>&1; then
  docker logs "$DRILL_NAME" > "$LOG_DIR/startup.log" 2>&1 || true
  echo "ERROR: isolated restore failed; details remain in the private diagnostic directory" >&2
  exit 1
fi

TABLES="$(docker exec "$DRILL_NAME" psql -X -U conectate -d "$TARGET_DB" -Atc \
  "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema');")"
[[ "$TABLES" =~ ^[0-9]+$ && "$TABLES" -gt 0 ]]
echo "RESTORE_ISOLATED_OK"
echo "RESTORED_TABLES=$TABLES"
echo "BACKUP=$BACKUP_DIR"
if (( WITH_ROLES )); then
  echo "SCOPE=isolated database, roles, ownership and ACL restore; application and media require separate checks"
else
  echo "SCOPE=database restore only; no production writes; no application startup; no file extraction"
fi
