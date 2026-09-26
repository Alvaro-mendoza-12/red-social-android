#!/usr/bin/env bash
set -Eeuo pipefail

if [ "${EUID:-$(id -u)}" -eq 0 ]; then
  echo "Ejecuta este instalador como el usuario operativo, no directamente como root."
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_USER="$(id -un)"
RUN_GROUP="$(id -gn)"
BACKUP_ROOT="${BACKUP_ROOT:-$HOME/conectate-backups}"
SYSTEMD_DIR="/etc/systemd/system"

chmod +x \
  "$ROOT_DIR/scripts/apply-root-ops-hotfix.sh" \
  "$ROOT_DIR/scripts/start-stack.sh" \
  "$ROOT_DIR/scripts/backup-ubuntu-primary.sh" \
  "$ROOT_DIR/scripts/verify-backup.sh" \
  "$ROOT_DIR/scripts/health-watchdog.sh" \
  "$ROOT_DIR/scripts/monitor-public-read-paths.sh" \
  "$ROOT_DIR/scripts/run-playmatch-catalog-sync.sh" \
  "$ROOT_DIR/scripts/restore-drill.sh"
mkdir -p "$BACKUP_ROOT"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

cat > "$tmp_dir/conectate-stack.service" <<EOF
[Unit]
Description=Conectate Ubuntu primary backend
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$RUN_USER
Group=$RUN_GROUP
WorkingDirectory=$ROOT_DIR
ExecStart=$ROOT_DIR/scripts/start-stack.sh
RemainAfterExit=yes
TimeoutStartSec=600

[Install]
WantedBy=multi-user.target
EOF

cat > "$tmp_dir/conectate-backup.service" <<EOF
[Unit]
Description=Verified daily backup for Conectate
After=conectate-stack.service

[Service]
Type=oneshot
User=$RUN_USER
Group=$RUN_GROUP
WorkingDirectory=$ROOT_DIR
Environment=BACKUP_ROOT=$BACKUP_ROOT
Environment=RETENTION_DAYS=7
ExecStart=$ROOT_DIR/scripts/backup-ubuntu-primary.sh
Nice=10
IOSchedulingClass=best-effort
IOSchedulingPriority=7
EOF

cat > "$tmp_dir/conectate-backup.timer" <<'EOF'
[Unit]
Description=Daily verified backup for Conectate

[Timer]
OnCalendar=*-*-* 03:15:00
RandomizedDelaySec=20m
Persistent=true
Unit=conectate-backup.service

[Install]
WantedBy=timers.target
EOF

cat > "$tmp_dir/conectate-health.service" <<EOF
[Unit]
Description=Conectate backend health watchdog
After=conectate-stack.service

[Service]
Type=oneshot
User=$RUN_USER
Group=$RUN_GROUP
WorkingDirectory=$ROOT_DIR
Environment=BACKUP_ROOT=$BACKUP_ROOT
ExecStart=$ROOT_DIR/scripts/health-watchdog.sh
EOF

cat > "$tmp_dir/conectate-health.timer" <<'EOF'
[Unit]
Description=Periodic Conectate backend health check

[Timer]
OnBootSec=2m
OnUnitActiveSec=2m
AccuracySec=20s
Persistent=true
Unit=conectate-health.service

[Install]
WantedBy=timers.target
EOF

cat > "$tmp_dir/conectate-public-read-paths.service" <<EOF
[Unit]
Description=NOVVIN public Social, Video and Messages read-path monitor
After=conectate-stack.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$RUN_USER
Group=$RUN_GROUP
WorkingDirectory=$ROOT_DIR
ExecStart=$ROOT_DIR/scripts/monitor-public-read-paths.sh
TimeoutStartSec=210
Nice=10
EOF

cat > "$tmp_dir/conectate-public-read-paths.timer" <<'EOF'
[Unit]
Description=Periodic NOVVIN public read-path monitoring

[Timer]
OnBootSec=5m
OnUnitActiveSec=10m
RandomizedDelaySec=45s
Persistent=true
Unit=conectate-public-read-paths.service

[Install]
WantedBy=timers.target
EOF

cat > "$tmp_dir/conectate-restore-drill.service" <<EOF
[Unit]
Description=Conectate isolated PostgreSQL restore drill
After=conectate-backup.service

[Service]
Type=oneshot
User=$RUN_USER
Group=$RUN_GROUP
WorkingDirectory=$ROOT_DIR
Environment=BACKUP_ROOT=$BACKUP_ROOT
ExecStart=$ROOT_DIR/scripts/restore-drill.sh
Nice=10
EOF

cat > "$tmp_dir/conectate-restore-drill.timer" <<'EOF'
[Unit]
Description=Weekly Conectate PostgreSQL restore drill

[Timer]
OnCalendar=Sun *-*-* 04:30:00
RandomizedDelaySec=20m
Persistent=true
Unit=conectate-restore-drill.service

[Install]
WantedBy=timers.target
EOF

cat > "$tmp_dir/conectate-playmatch-catalog-sync.service" <<EOF
[Unit]
Description=Incremental PlayMatch game catalog synchronization
After=conectate-stack.service network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$RUN_USER
Group=$RUN_GROUP
WorkingDirectory=$ROOT_DIR
Environment=PLAYMATCH_CATALOG_PROVIDER=wikidata
Environment=PLAYMATCH_CATALOG_PAGE_SIZE=100
Environment=PLAYMATCH_CATALOG_MAX_PAGES=3
ExecStart=$ROOT_DIR/scripts/run-playmatch-catalog-sync.sh
Nice=10
TimeoutStartSec=1300
EOF

cat > "$tmp_dir/conectate-playmatch-catalog-sync.timer" <<'EOF'
[Unit]
Description=Daily incremental PlayMatch catalog synchronization

[Timer]
OnCalendar=*-*-* 04:50:00
RandomizedDelaySec=30m
Persistent=true
Unit=conectate-playmatch-catalog-sync.service

[Install]
WantedBy=timers.target
EOF

sudo install -m 0644 "$tmp_dir"/*.service "$tmp_dir"/*.timer "$SYSTEMD_DIR/"
sudo systemctl daemon-reload
sudo systemctl enable --now conectate-stack.service conectate-backup.timer conectate-health.timer conectate-public-read-paths.timer conectate-restore-drill.timer conectate-playmatch-catalog-sync.timer

if [ "${INSTALL_OPERATIONS_SKIP_INITIAL_VALIDATION:-false}" = "true" ]; then
  echo "==> Instalacion de unidades completada; validacion inicial omitida de forma explicita"
else
  echo "==> Ejecutando primera copia verificada"
  sudo systemctl start conectate-backup.service
  sudo systemctl start conectate-health.service
  sudo systemctl start conectate-public-read-paths.service
fi

systemctl --no-pager --full status conectate-stack.service conectate-backup.timer conectate-health.timer conectate-public-read-paths.timer conectate-playmatch-catalog-sync.timer || true
systemctl list-timers --all --no-pager | grep -E 'conectate-(backup|health|public-read-paths|playmatch)' || true
if [ "${INSTALL_OPERATIONS_SKIP_INITIAL_VALIDATION:-false}" != "true" ]; then
  "$ROOT_DIR/scripts/verify-backup.sh" "$BACKUP_ROOT/latest"
  "$ROOT_DIR/scripts/restore-drill.sh" "$BACKUP_ROOT/latest"
fi

echo "OPERATIONS_READY=true"
echo "BACKUP_ROOT=$BACKUP_ROOT"
