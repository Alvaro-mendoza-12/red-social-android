#!/usr/bin/env bash
set -Eeuo pipefail

OUT_DIR="${OUT_DIR:-$HOME/conectate-power-diagnostics}"
mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%dT%H%M%S)"
OUT="$OUT_DIR/power-diagnose-$STAMP.txt"

{
  echo "=== CONECTATE POWER DIAGNOSE ==="
  date -Is
  echo

  echo "=== HOST ==="
  hostname
  uname -a
  uptime
  who -a || true
  echo

  echo "=== LAST REBOOTS / SHUTDOWNS ==="
  last -x | head -80 || true
  echo

  echo "=== SLEEP TARGETS ==="
  systemctl status sleep.target suspend.target hibernate.target hybrid-sleep.target --no-pager || true
  echo

  echo "=== LOGIND CONFIG ==="
  cat /etc/systemd/logind.conf 2>/dev/null || true
  echo "--- drop-ins ---"
  find /etc/systemd/logind.conf.d -maxdepth 1 -type f -print -exec sed -n '1,160p' {} \; 2>/dev/null || true
  echo

  echo "=== INHIBITORS ==="
  systemd-inhibit --list || true
  echo

  echo "=== CONECTATE INHIBIT SERVICE ==="
  systemctl status conectate-inhibit-sleep.service --no-pager || true
  echo

  echo "=== KERNEL CMDLINE ==="
  cat /proc/cmdline || true
  echo

  echo "=== POWER SYSFS ==="
  cat /sys/power/state 2>/dev/null || true
  cat /sys/power/mem_sleep 2>/dev/null || true
  echo

  echo "=== NETWORK ==="
  ip -brief addr || true
  ip route || true
  IFACE="$(ip -o -4 route show to default | awk '{print $5; exit}' || true)"
  if [[ -n "${IFACE:-}" ]]; then
    echo "--- iface=$IFACE ---"
    ethtool "$IFACE" 2>/dev/null || true
    cat "/sys/class/net/$IFACE/device/power/control" 2>/dev/null || true
  fi
  echo

  echo "=== USB POWER CONTROL ==="
  for f in /sys/bus/usb/devices/*/power/control; do
    [[ -e "$f" ]] || continue
    echo "$f=$(cat "$f" 2>/dev/null || true)"
  done
  echo

  echo "=== DISKS ==="
  lsblk -o NAME,SIZE,FSTYPE,LABEL,MOUNTPOINT,MODEL,TRAN || true
  df -h || true
  echo

  echo "=== CONECTATE STATUS ==="
  "$HOME/bin/conectate-status" || true
  echo

  echo "=== JOURNAL BOOT CURRENT POWER EVENTS ==="
  journalctl -b --no-pager | grep -Ei 'suspend|hibernate|sleep|freeze|thaw|PM:|ACPI|power key|lid|watchdog|hung|blocked|reset|thermal|oom|panic|irq|nobody cared|usb|xhci|Realtek|r816|link is down|link is up' | tail -240 || true
  echo

  echo "=== JOURNAL PREVIOUS BOOT POWER EVENTS ==="
  journalctl -b -1 --no-pager 2>/dev/null | grep -Ei 'suspend|hibernate|sleep|freeze|thaw|PM:|ACPI|power key|lid|watchdog|hung|blocked|reset|thermal|oom|panic|irq|nobody cared|usb|xhci|Realtek|r816|link is down|link is up' | tail -260 || true
} | tee "$OUT"

echo
echo "POWER_DIAGNOSE_OUT=$OUT"

