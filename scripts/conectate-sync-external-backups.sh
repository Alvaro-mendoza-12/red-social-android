#!/usr/bin/env bash
set -euo pipefail

SOURCE="${BACKUP_ROOT:-$HOME/conectate-backups}"

if [ ! -d "$SOURCE" ]; then
  echo "BACKUP_SOURCE_MISSING=$SOURCE"
  exit 0
fi

sync_target() {
  local mountpoint="$1"
  local target="$mountpoint/conectate-backups"

  if ! findmnt -rn "$mountpoint" >/dev/null 2>&1; then
    echo "SKIP_NOT_MOUNTED=$mountpoint"
    return 0
  fi

  mkdir -p "$target"
  rsync -a --delete "$SOURCE"/ "$target"/
  echo "SYNC_OK=$target"
}

sync_target /mnt/conectate-archive
sync_target /mnt/conectate-backup
