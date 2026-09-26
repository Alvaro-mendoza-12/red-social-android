#!/usr/bin/env bash
set -Eeuo pipefail

# Installs a remotely-managed Cloudflare Tunnel token without writing it into
# this repository, Docker Compose files, shell history, or a process command
# line. Run it only on the Ubuntu host after Cloudflare has created the named
# tunnel and public hostname.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_SOURCE="$ROOT_DIR/systemd/novvin-cloudflared.service"
SERVICE_DESTINATION="/etc/systemd/system/novvin-cloudflared.service"
SECRETS_DIRECTORY="/etc/novvin"
SECRETS_FILE="$SECRETS_DIRECTORY/cloudflared.env"

if [ "${EUID}" -ne 0 ]; then
  echo "ERROR: ejecuta con sudo; la unidad y el secreto viven bajo /etc." >&2
  exit 1
fi

if [ "$#" -ne 1 ]; then
  echo "Uso: sudo $0 /ruta/segura/al/archivo-del-token" >&2
  exit 2
fi

TOKEN_SOURCE="$1"
if [ ! -f "$SERVICE_SOURCE" ]; then
  echo "ERROR: no existe la plantilla systemd: $SERVICE_SOURCE" >&2
  exit 1
fi
if [ ! -r "$TOKEN_SOURCE" ]; then
  echo "ERROR: no se puede leer el archivo de token indicado" >&2
  exit 1
fi
if ! command -v cloudflared >/dev/null 2>&1; then
  echo "ERROR: cloudflared no está instalado o no está en PATH" >&2
  exit 1
fi

TUNNEL_TOKEN="$(tr -d '\r\n' < "$TOKEN_SOURCE")"
trap 'unset TUNNEL_TOKEN' EXIT
if [ "${#TUNNEL_TOKEN}" -lt 32 ] || [[ "$TUNNEL_TOKEN" == *[[:space:]]* ]]; then
  echo "ERROR: el token del túnel es inválido o contiene espacios." >&2
  exit 1
fi

install -d -m 700 "$SECRETS_DIRECTORY"
temporary_secret="$(mktemp "$SECRETS_DIRECTORY/.cloudflared.env.XXXXXX")"
chmod 600 "$temporary_secret"
printf 'TUNNEL_TOKEN=%s\n' "$TUNNEL_TOKEN" > "$temporary_secret"
mv -f "$temporary_secret" "$SECRETS_FILE"
chmod 600 "$SECRETS_FILE"
install -m 644 "$SERVICE_SOURCE" "$SERVICE_DESTINATION"

systemctl daemon-reload
systemctl enable --now novvin-cloudflared.service
systemctl is-active --quiet novvin-cloudflared.service

echo "NAMED_CLOUDFLARE_TUNNEL_READY=true"
echo "Verifica el hostname público con: curl -fsS https://TU_DOMINIO/ready"
