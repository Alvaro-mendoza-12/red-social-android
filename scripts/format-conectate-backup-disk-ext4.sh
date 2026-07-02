#!/usr/bin/env bash
set -euo pipefail

DEV="${1:-/dev/sdb1}"
MOUNTPOINT="/mnt/conectate-backup"
LABEL="CONECTATE-BACKUP"
EXPECTED_OLD_UUID="F8A8B4FEA8B4BD06"

if [ "$(id -u)" -ne 0 ]; then
  echo "Ejecuta con sudo: sudo bash $0"
  exit 1
fi

if [ ! -b "$DEV" ]; then
  echo "ERROR: $DEV no existe o no es bloque."
  lsblk -o NAME,SIZE,FSTYPE,LABEL,UUID,MOUNTPOINT,MODEL,TRAN
  exit 1
fi

current_uuid="$(blkid -s UUID -o value "$DEV" 2>/dev/null || true)"
current_type="$(blkid -s TYPE -o value "$DEV" 2>/dev/null || true)"
current_label="$(blkid -s LABEL -o value "$DEV" 2>/dev/null || true)"

echo "DISCO_DETECTADO=$DEV"
echo "TYPE=$current_type"
echo "LABEL=$current_label"
echo "UUID=$current_uuid"
lsblk -o NAME,SIZE,FSTYPE,LABEL,UUID,MOUNTPOINT,MODEL,TRAN "$DEV"

if findmnt -rn "$DEV" >/dev/null 2>&1; then
  echo "ERROR: $DEV está montado. Desmonta antes de formatear."
  exit 1
fi

if [ "$current_uuid" != "$EXPECTED_OLD_UUID" ] && [ "$current_type" != "ntfs" ]; then
  echo "ERROR: No parece ser el disco NTFS viejo esperado. Cancelo por seguridad."
  exit 1
fi

echo
echo "PELIGRO: esto BORRARÁ TODO el contenido de $DEV y lo convertirá a ext4."
echo "Debe ser el disco USB de 465.8G que antes decía UBUNTU-SERV."
read -r -p "Escribe FORMATEAR para continuar: " confirm
if [ "$confirm" != "FORMATEAR" ]; then
  echo "Cancelado."
  exit 1
fi

echo "==> Formateando $DEV como ext4..."
mkfs.ext4 -F -L "$LABEL" "$DEV"

new_uuid="$(blkid -s UUID -o value "$DEV")"
echo "NEW_UUID=$new_uuid"

echo "==> Configurando montaje automático..."
mkdir -p "$MOUNTPOINT"
cp /etc/fstab "/etc/fstab.bak.backup-disk-$(date +%Y%m%dT%H%M%S)"
awk '$2 != "/mnt/conectate-backup" {print}' /etc/fstab > /tmp/conectate-fstab.new
echo "UUID=$new_uuid $MOUNTPOINT ext4 defaults,nofail,x-systemd.device-timeout=10 0 2" >> /tmp/conectate-fstab.new
install -m 0644 /tmp/conectate-fstab.new /etc/fstab
systemctl daemon-reload
mount -a

echo "==> Preparando carpetas de backup..."
chown alvaro:alvaro "$MOUNTPOINT"
chmod 775 "$MOUNTPOINT"
mkdir -p "$MOUNTPOINT/conectate-backups"
chown -R alvaro:alvaro "$MOUNTPOINT/conectate-backups"

if [ -d /home/alvaro/conectate-backups ]; then
  echo "==> Copiando backups actuales al disco nuevo..."
  rsync -a --delete /home/alvaro/conectate-backups/ "$MOUNTPOINT/conectate-backups/"
fi

echo "==> Verificación final"
df -hT "$MOUNTPOINT"
du -sh "$MOUNTPOINT/conectate-backups" 2>/dev/null || true
lsblk -o NAME,SIZE,FSTYPE,LABEL,UUID,MOUNTPOINT,MODEL,TRAN "$DEV"
echo "BACKUP_DISK_EXT4_OK=true"
