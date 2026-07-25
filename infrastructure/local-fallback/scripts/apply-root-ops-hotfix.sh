#!/usr/bin/env bash
set -Eeuo pipefail

if [ "${EUID:-$(id -u)}" -ne 0 ]; then
  echo "Ejecuta este script con sudo." >&2
  exit 1
fi

ROOT_DIR="${CONECTATE_ROOT_DIR:-/home/alvaro/conectate-local-fallback}"
START_SCRIPT="$ROOT_DIR/scripts/start-stack.sh"
DROPIN_DIR="/etc/systemd/system/conectate-stack.service.d"
DROPIN_FILE="$DROPIN_DIR/10-reliable-start.conf"
BACKUP_DIR="/var/backups/conectate-systemd/$(date -u +%Y%m%dT%H%M%SZ)"

if [ ! -x "$START_SCRIPT" ]; then
  echo "No existe el iniciador ejecutable: $START_SCRIPT" >&2
  exit 1
fi

install -d -m 0755 "$BACKUP_DIR" "$DROPIN_DIR"
if [ -f "$DROPIN_FILE" ]; then
  cp --preserve=all "$DROPIN_FILE" "$BACKUP_DIR/"
fi
cp --preserve=all /etc/systemd/system/conectate-stack.service "$BACKUP_DIR/"

tmp_file="$(mktemp)"
trap 'rm -f "$tmp_file"' EXIT
cat > "$tmp_file" <<EOF
[Service]
ExecStart=
ExecStart=$START_SCRIPT
TimeoutStartSec=600
EOF
install -m 0644 "$tmp_file" "$DROPIN_FILE"

systemctl daemon-reload
systemctl reset-failed conectate-stack.service
systemctl restart conectate-stack.service
systemctl is-active --quiet conectate-stack.service

echo "CONECTATE_STACK_ROOT_HOTFIX=applied"
echo "BACKUP_DIR=$BACKUP_DIR"
systemctl show conectate-stack.service \
  -p ActiveState -p SubState -p UnitFileState -p ExecMainStatus --no-pager
