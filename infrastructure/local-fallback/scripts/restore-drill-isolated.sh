#!/usr/bin/env bash
set -Eeuo pipefail

# No production DB connection, network, host port, writable backup, or durable volume.
# Usage: bash scripts/restore-drill-isolated.sh /absolute/path/to/verified/backup
BACKUP_DIR="$(readlink -f "${1:?Provide the directory of a verified backup}")"
test -d "$BACKUP_DIR"
test -s "$BACKUP_DIR/postgres.dump"
IMAGE="postgres:16.15-alpine"
docker image inspect "$IMAGE" >/dev/null
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
  --mount "type=bind,source=$BACKUP_DIR/postgres.dump,target=/backup/postgres.dump,readonly" \
  --env POSTGRES_USER=conectate --env POSTGRES_DB=novvin_restore_drill \
  --env POSTGRES_HOST_AUTH_METHOD=trust \
  "$IMAGE" > "$LOG_DIR/container-id"
CREATED=1
READY=0
for attempt in $(seq 1 30); do
  if docker exec "$DRILL_NAME" pg_isready -U conectate -d novvin_restore_drill >/dev/null 2>&1; then
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

if ! docker exec "$DRILL_NAME" pg_restore -U conectate -d novvin_restore_drill \
  --exit-on-error --no-owner --no-privileges /backup/postgres.dump \
  > "$LOG_DIR/restore.log" 2>&1; then
  echo "ERROR: isolated restore failed; details remain in the private diagnostic directory" >&2
  exit 1
fi

TABLES="$(docker exec "$DRILL_NAME" psql -X -U conectate -d novvin_restore_drill -Atc \
  "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema');")"
[[ "$TABLES" =~ ^[0-9]+$ && "$TABLES" -gt 0 ]]
echo "RESTORE_ISOLATED_OK"
echo "RESTORED_TABLES=$TABLES"
echo "BACKUP=$BACKUP_DIR"
echo "SCOPE=database restore only; no production writes; no application startup; no file extraction"
