#!/usr/bin/env bash
# Host maintenance for the audited Ubuntu 24.04 server. Docker/Tailscale package
# upgrades can interrupt connections; run --apply in a maintenance window via
# systemd-run so a lost SSH connection cannot interrupt dpkg.
set -Eeuo pipefail

mode="${1:---plan}"
if [[ "$mode" != --plan && "$mode" != --apply ]]; then
  printf 'Usage: %s [--plan|--apply]\n' "$0" >&2
  exit 64
fi
if [[ "$EUID" -ne 0 ]]; then
  printf 'Administrator privileges are required. Invoke this script with sudo.\n' >&2
  exit 77
fi
if [[ "$(hostname)" != conectate ]]; then
  printf 'Unexpected host; no packages changed.\n' >&2
  exit 78
fi
# shellcheck source=/etc/os-release
source /etc/os-release
if [[ "$ID" != ubuntu || "$VERSION_ID" != 24.04 ]]; then
  printf 'This plan supports Ubuntu 24.04 only; no release upgrade is performed.\n' >&2
  exit 78
fi

log_dir="/var/log/novvin-host-maintenance/$(date -u +%Y%m%dT%H%M%SZ)"
install -d -m 0750 -o root -g adm "$log_dir"
exec > >(tee "$log_dir/run.log") 2>&1
trap 'status=$?; printf "exit=%s\n" "$status" > "$log_dir/result"; chmod 0640 "$log_dir"/*; chgrp adm "$log_dir"/*' EXIT
printf 'Mode: %s; host: %s; log: %s\n' "$mode" "$(hostname)" "$log_dir"
dpkg-query -W > "$log_dir/packages-before.txt"
docker ps --format '{{.Names}} {{.Image}} {{.Status}}' > "$log_dir/containers-before.txt"
if (( $(df --output=avail -k / | tail -n 1) < 2097152 )); then
  printf 'Less than 2 GiB free on the root filesystem; no packages changed.\n' >&2
  exit 75
fi

apt-get update
apt-get -s --no-remove upgrade | tee "$log_dir/upgrade-plan.txt"
if [[ "$mode" == --plan ]]; then
  printf 'Plan complete. No installed packages changed.\n'
  exit 0
fi

deployment=/home/alvaro/novvin-candidates/security-20261026-deps/state/deployment-status.json
python3 - "$deployment" <<'PY'
import json, pathlib, sys
data = json.loads(pathlib.Path(sys.argv[1]).read_text())
if data.get('success') is not True:
    raise SystemExit('Container deployment has not passed its checks; host upgrade stopped.')
backup = json.loads(pathlib.Path(sys.argv[1]).with_name('backup-status.json').read_text())
if backup.get('database', {}).get('isolatedRestore') != 'passed':
    raise SystemExit('A verified isolated backup restoration is required.')
PY
export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=l
apt-get -y --no-remove -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade
dpkg --audit | tee "$log_dir/dpkg-audit.txt"
if [[ -s "$log_dir/dpkg-audit.txt" ]]; then
  printf 'dpkg reports incomplete package work; inspect the retained log.\n' >&2
  exit 1
fi

ready=false
for attempt in {1..30}; do
  if curl --fail --silent --show-error --max-time 5 https://conectate.tail9b267c.ts.net/ready > "$log_dir/ready.json"; then
    ready=true
    break
  fi
  sleep 5
done
docker ps --format '{{.Names}} {{.Image}} {{.Status}}' | tee "$log_dir/containers-after.txt"
systemctl --failed --no-legend | tee "$log_dir/failed-units.txt"
if [[ "$ready" != true ]]; then
  printf 'The public readiness endpoint did not recover. Inspect the retained diagnostics.\n' >&2
  exit 1
fi
if [[ -f /var/run/reboot-required ]]; then
  printf 'A reboot is required to finish applying some updates. No automatic reboot performed.\n'
else
  printf 'No reboot-required flag is present.\n'
fi
printf 'Host package update completed; public readiness passed.\n'
