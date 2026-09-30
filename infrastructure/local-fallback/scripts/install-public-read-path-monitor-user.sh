#!/usr/bin/env bash
set -Eeuo pipefail

if [ "${EUID:-$(id -u)}" -eq 0 ]; then
  echo "Ejecuta este instalador como el usuario operativo, no como root."
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
USER_SYSTEMD_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"

chmod 755 "$ROOT_DIR/scripts/monitor-public-read-paths.sh"
install -d -m 700 "$USER_SYSTEMD_DIR"

service_tmp="$(mktemp)"
timer_tmp="$(mktemp)"
trap 'rm -f "$service_tmp" "$timer_tmp"' EXIT

cat > "$service_tmp" <<EOF
[Unit]
Description=NOVVIN public Social, Video and Messages read-path monitor
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
WorkingDirectory=$ROOT_DIR
ExecStart=$ROOT_DIR/scripts/monitor-public-read-paths.sh
TimeoutStartSec=390
Nice=10
EOF

cat > "$timer_tmp" <<'EOF'
[Unit]
Description=Periodic NOVVIN public read-path monitoring

[Timer]
OnBootSec=60s
OnUnitInactiveSec=90s
RandomizedDelaySec=15s
Persistent=true
Unit=conectate-public-read-paths.service

[Install]
WantedBy=timers.target
EOF

install -m 0644 "$service_tmp" "$USER_SYSTEMD_DIR/conectate-public-read-paths.service"
install -m 0644 "$timer_tmp" "$USER_SYSTEMD_DIR/conectate-public-read-paths.timer"
systemctl --user daemon-reload
systemctl --user enable --now conectate-public-read-paths.timer
systemctl --user start conectate-public-read-paths.service
systemctl --user --no-pager --full status conectate-public-read-paths.timer

echo "PUBLIC_READ_PATH_MONITOR_READY=true"
