#!/usr/bin/env bash
# This machine-specific migration requires explicit authorization to erase the
# SN530 serial below. No other disk is eligible, even when device names change.
set -Eeuo pipefail
mode=${1:?Use prepare, copy, or cutover}
device=/dev/nvme0n1
partition=/dev/nvme0n1p1
fast=/mnt/conectate-fast
project=/home/alvaro/conectate-local-fallback
state=/home/alvaro/conectate-ssd-migration
expected_serial=22021H800152
test "$(id -u)" = 0
test "$(tr -d '[:space:]' </sys/class/nvme/nvme0/serial)" = "$expected_serial"
test "$(lsblk -dn -o ROTA "$device" | tr -d ' ')" = 0
size=$(blockdev --getsize64 "$device")
test "$size" -gt 500000000000 && test "$size" -lt 520000000000
mkdir -p "$state"

assert_fast_mount() {
  mountpoint -q "$fast"
  test "$(findmnt -n -o FSTYPE --target "$fast")" = ext4
  test "$(readlink -f "$(findmnt -n -o SOURCE --target "$fast")")" = "$partition"
  test "$(readlink -f "$fast")" = /mnt/conectate-fast
}

if [ "$mode" = prepare ]; then
  # Do not erase a root/boot/storage disk or any disk with mounted children.
  test "$(findmnt -no SOURCE /)" != "$device"
  if lsblk -nr -o MOUNTPOINTS "$device" | grep -q '[^[:space:]]'; then
    echo 'The authorized NVMe has a mounted partition; aborting.' >&2; exit 1
  fi
  test ! -e "$state/formatted-uuid"
  sfdisk --dump "$device" >"$state/previous-partition-table.txt"
  cp -a /etc/fstab "$state/fstab.before-prepare"
  parted --script "$device" mklabel gpt mkpart novvin-fast ext4 1MiB 100%
  partprobe "$device"
  udevadm settle
  test "$(lsblk -no PKNAME "$partition")" = nvme0n1
  mkfs.ext4 -F -L novvin-fast -m 1 "$partition"
  uuid=$(blkid -s UUID -o value "$partition")
  test -n "$uuid"
  printf '%s\n' "$uuid" >"$state/formatted-uuid"
  mkdir -p "$fast"
  printf 'UUID=%s %s ext4 defaults,noatime,nofail,x-systemd.device-timeout=10 0 2\n' "$uuid" "$fast" >>/etc/fstab
  systemctl daemon-reload
  mount "$fast"
  assert_fast_mount
  mkdir -p "$fast/docker" "$fast/containerd"
  findmnt --target "$fast"
  df -hT "$fast"
  echo 'PREPARE_OK'
  exit
fi

assert_fast_mount
test "$(readlink -f /var/lib/docker)" = /var/lib/docker
test "$(readlink -f /var/lib/containerd)" = /var/lib/containerd
if mountpoint -q /var/lib/docker || mountpoint -q /var/lib/containerd; then
  echo 'Docker storage is already mounted; refusing to copy onto itself.' >&2; exit 1
fi
test "$(findmnt -no SOURCE --target /var/lib/docker)" != "$partition"
test "$(findmnt -no SOURCE --target /var/lib/containerd)" != "$partition"
test -d /var/lib/docker/volumes
test -d /var/lib/containerd/io.containerd.content.v1.content

copy_data() {
  # -x skips live container mounts; metadata/permissions/hardlinks are retained.
  # Source originals remain untouched for rollback. Final copy is done stopped.
  local result
  local options=(-aHAXx --numeric-ids)
  # The stopped final copy must also remove snapshots deleted during live copy.
  # Targets are fixed children of the verified authorized mount above.
  if [ "$mode" = cutover ]; then options+=(--delete); fi
  for directory in docker containerd; do
    test "$(readlink -f "$fast/$directory")" = "$fast/$directory"
    result=0
    rsync "${options[@]}" "/var/lib/$directory/" "$fast/$directory/" || result=$?
    # A live daemon can delete cache snapshots while rsync is walking them.
    # Accept only 'vanished source' here; the stopped final pass remains strict.
    if [ "$result" -ne 0 ] && ! { [ "$mode" = copy ] && [ "$result" -eq 24 ]; }; then
      return "$result"
    fi
  done
}
if [ "$mode" = copy ]; then
  copy_data
  echo 'LIVE_COPY_OK'
  exit
fi
test "$mode" = cutover
test -s "$project/../conectate-backups/latest/postgres.dump"
test -s "$project/../conectate-backups/latest/SHA256SUMS"
test ! -e /etc/systemd/system/docker.service.d/novvin-fast-storage.conf
test ! -e /etc/systemd/system/containerd.service.d/novvin-fast-storage.conf
cp -a /etc/fstab "$state/fstab.before-cutover"
docker ps --format '{{.Names}}' >"$state/running-containers.txt"
started=$(date +%s)

rollback() {
  rc=$?
  trap - ERR
  echo 'Cutover failed; restoring the original Docker and containerd storage.' >&2
  systemctl stop docker.service docker.socket containerd.service || true
  mountpoint -q /var/lib/docker && umount /var/lib/docker || true
  mountpoint -q /var/lib/containerd && umount /var/lib/containerd || true
  cp -a "$state/fstab.before-cutover" /etc/fstab
  rm -f /etc/systemd/system/docker.service.d/novvin-fast-storage.conf /etc/systemd/system/containerd.service.d/novvin-fast-storage.conf
  systemctl daemon-reload
  systemctl start containerd.service docker.socket docker.service
  (cd "$project" && docker compose up -d) || true
  exit "$rc"
}
trap rollback ERR
cd "$project"
docker compose stop -t 30
systemctl stop docker.service docker.socket containerd.service
copy_data
# Keep original paths: existing named volumes, backup scripts and containers use
# those paths. Both Docker's volume store and containerd's image store move.
printf '%s/docker /var/lib/docker none bind,nofail,x-systemd.requires=%s 0 0\n' "$fast" "$fast" >>/etc/fstab
printf '%s/containerd /var/lib/containerd none bind,nofail,x-systemd.requires=%s 0 0\n' "$fast" "$fast" >>/etc/fstab
mkdir -p /etc/systemd/system/docker.service.d /etc/systemd/system/containerd.service.d
printf '[Unit]\nRequiresMountsFor=%s /var/lib/docker /var/lib/containerd\n' "$fast" > /etc/systemd/system/docker.service.d/novvin-fast-storage.conf
cp /etc/systemd/system/docker.service.d/novvin-fast-storage.conf /etc/systemd/system/containerd.service.d/novvin-fast-storage.conf
systemctl daemon-reload
mount /var/lib/docker
mount /var/lib/containerd
test "$(findmnt -no SOURCE --target /var/lib/docker)" = "$partition[/docker]"
test "$(findmnt -no SOURCE --target /var/lib/containerd)" = "$partition[/containerd]"
systemctl start containerd.service docker.socket docker.service
docker compose up -d
for attempt in $(seq 1 30); do
  if curl -fsS --max-time 3 http://127.0.0.1:3001/ready >"$state/ready-after-cutover.json"; then break; fi
  sleep 2
done
python3 -c 'import json; import sys; assert json.load(open(sys.argv[1]))["ok"]' "$state/ready-after-cutover.json"
while IFS= read -r name; do
  test "$(docker inspect --format '{{.State.Running}}' "$name")" = true
done <"$state/running-containers.txt"
docker compose exec -T db pg_isready -U conectate -d conectate_fallback
trap - ERR
sync
printf 'CUTOVER_OK downtime_seconds=%s\n' "$(( $(date +%s) - started ))"
findmnt --target /var/lib/docker
findmnt --target /var/lib/containerd
df -hT "$fast"
