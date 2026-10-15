#!/usr/bin/env bash
set -Eeuo pipefail

# Administrative backup reads both application-owned media and root-owned
# security journals, without making either accessible to the serving process.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
API_CONTAINER="$(docker compose ps -q api)"
[[ "$API_CONTAINER" =~ ^[a-f0-9]{64}$ ]] || { echo "ERROR: expected one API container" >&2; exit 1; }
API_IMAGE="$(docker inspect --format '{{.Image}}' "$API_CONTAINER")"
[[ "$API_IMAGE" =~ ^sha256:[a-f0-9]{64}$ ]]
STORAGE_MOUNT="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data/storage"}}{{.Type}}|{{.Name}}|{{.Source}}{{end}}{{end}}' "$API_CONTAINER")"
IFS='|' read -r MOUNT_TYPE VOLUME_NAME MOUNT_SOURCE <<< "$STORAGE_MOUNT"
[[ "$MOUNT_TYPE" = volume && "$VOLUME_NAME" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]+$ ]] || { echo "ERROR: expected a named storage volume" >&2; exit 1; }
ARCHIVE_NAME="novvin-storage-archive-$(date -u +%Y%m%d%H%M%S)-$$"
cleanup() {
  local result=$?
  trap - EXIT
  if docker container inspect "$ARCHIVE_NAME" >/dev/null 2>&1; then
    local owner
    owner="$(docker inspect --format '{{ index .Config.Labels "novvin.storage-archive" }}' "$ARCHIVE_NAME")"
    if [[ "$owner" = "$ARCHIVE_NAME" ]]; then docker rm -f "$ARCHIVE_NAME" >/dev/null; else result=1; fi
  fi
  exit "$result"
}
trap cleanup EXIT
# No ports, network, host mount or writable application volume. DAC_READ_SEARCH
# permits reading protected owner directories; DAC_OVERRIDE is not granted.
docker run --rm --name "$ARCHIVE_NAME" --label "novvin.storage-archive=$ARCHIVE_NAME" \
  --network none --read-only --user 0:0 --cap-drop ALL --cap-add DAC_READ_SEARCH \
  --security-opt no-new-privileges --memory 256m --memory-swap 256m --cpus 1 --pids-limit 64 \
  --mount "type=volume,source=$VOLUME_NAME,target=/backup-storage,readonly" \
  --entrypoint tar "$API_IMAGE" -C /backup-storage -czf - .
