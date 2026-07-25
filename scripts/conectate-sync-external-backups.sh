#!/usr/bin/env bash
set -euo pipefail

SOURCE="${BACKUP_ROOT:-$HOME/conectate-backups}"

if [ ! -d "$SOURCE" ]; then
  echo "BACKUP_SOURCE_MISSING=$SOURCE"
  exit 0
fi

sync_target() {
  local mountpoint="$1"
  local target="${2:-$mountpoint/conectate-backups}"
  local latest_source
  local latest_name

  if ! findmnt -rn "$mountpoint" >/dev/null 2>&1; then
    echo "SKIP_NOT_MOUNTED=$mountpoint"
    return 0
  fi

  mkdir -p "$target"
  rsync -a --delete "$SOURCE"/ "$target"/

  # Keep the external copy self-contained if the source uses an absolute symlink.
  latest_source="$(readlink -f "$SOURCE/latest" 2>/dev/null || true)"
  if [[ "$latest_source" == "$SOURCE/"* ]]; then
    latest_name="${latest_source##*/}"
    if [[ -d "$target/$latest_name" ]]; then
      ln -sfn "$latest_name" "$target/latest"
    fi
  fi

  if [ ! -f "$target/latest/SHA256SUMS" ]; then
    echo "ERROR: falta SHA256SUMS en $target/latest" >&2
    return 1
  fi
  (cd "$target/latest" && sha256sum -c SHA256SUMS >/dev/null)
  echo "SYNC_OK=$target"
}

sync_target \
  /mnt/conectate-media \
  /mnt/conectate-media/conectate-storage/.conectate-backups
sync_target /mnt/conectate-archive
sync_target /mnt/conectate-backup
