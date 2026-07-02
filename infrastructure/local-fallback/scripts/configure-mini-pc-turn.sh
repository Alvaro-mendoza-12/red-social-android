#!/usr/bin/env bash
set -Eeuo pipefail

if [ "${EUID:-$(id -u)}" -eq 0 ]; then
  echo "Ejecuta este script como tu usuario normal con acceso a sudo."
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TURN_HOST_INPUT="${1:-${TURN_HOST:-}}"
PUBLIC_IP_INPUT="${2:-${TURN_PUBLIC_IP:-}}"

[ -n "$TURN_HOST_INPUT" ] || {
  echo "Uso: $0 turn.tudominio.com [IP_PUBLICA]"
  echo "También puedes usar tu dominio DuckDNS si apunta a la IP pública de casa."
  echo "Nota CGNAT: Cloudflare Tunnel no transporta TURN/WebRTC media; necesitas puertos públicos reales o TURN externo/VPS."
  exit 2
}

command -v openssl >/dev/null
command -v curl >/dev/null
cd "$ROOT_DIR"
test -s .env

public_ip="$PUBLIC_IP_INPUT"
if [ -z "$public_ip" ]; then
  public_ip="$(curl -fsS --max-time 15 https://api.ipify.org | tr -d '[:space:]')"
fi
if ! [[ "$public_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ERROR: no se pudo determinar una IPv4 pública válida."
  exit 2
fi

local_ip="$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="src") {print $(i+1); exit}}')"
if ! [[ "$local_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ERROR: no se pudo determinar la IPv4 local de la mini PC."
  exit 2
fi

env_value() {
  sed -n "s/^$1=//p" .env | tail -n 1
}

set_env() {
  local key="$1" value="$2" temporary
  temporary="$(mktemp)"
  awk -v key="$key" -v value="$value" '
    BEGIN { written = 0 }
    $0 ~ "^" key "=" {
      if (!written) print key "=" value
      written = 1
      next
    }
    { print }
    END { if (!written) print key "=" value }
  ' .env > "$temporary"
  cat "$temporary" > .env
  rm -f "$temporary"
}

turn_secret="$(env_value TURN_SECRET)"
if [ -z "$turn_secret" ] || [ "$turn_secret" = "change-this-with-openssl-rand-hex-32" ]; then
  turn_secret="$(openssl rand -hex 32)"
fi

set_env TURN_HOST "$TURN_HOST_INPUT"
set_env TURN_REALM "$TURN_HOST_INPUT"
set_env TURN_SECRET "$turn_secret"
set_env TURN_PORT "3478"
set_env TURN_TLS_PORT "5349"
set_env TURN_EXTERNAL_IP "$public_ip/$local_ip"
set_env TURN_MIN_PORT "49160"
set_env TURN_MAX_PORT "49200"
set_env TURN_TTL_SECONDS "3600"
set_env TURN_ENABLE_UDP "true"
set_env TURN_ENABLE_TCP "true"
set_env TURN_ENABLE_TLS "false"
chmod 600 .env

echo "==> Activando Coturn y actualizando signaling"
sudo docker compose --profile turn pull coturn
sudo docker compose --profile turn up -d coturn
sudo docker compose up -d --no-deps --force-recreate signaling

for _ in $(seq 1 45); do
  code="$(curl -sS -o /tmp/conectate-turn-check.json -w '%{http_code}' \
    http://127.0.0.1:${FALLBACK_EDGE_PORT:-3080}/turn-credentials || true)"
  if [ "$code" = "401" ] || [ "$code" = "200" ]; then
    break
  fi
  sleep 2
done
if [ "$code" != "401" ] && [ "$code" != "200" ]; then
  cat /tmp/conectate-turn-check.json 2>/dev/null || true
  echo "ERROR: signaling todavía no ofrece credenciales TURN (HTTP $code)."
  exit 1
fi
rm -f /tmp/conectate-turn-check.json

if command -v ufw >/dev/null && sudo ufw status | grep -q '^Status: active'; then
  sudo ufw allow 3478/tcp comment 'Conectate TURN TCP'
  sudo ufw allow 3478/udp comment 'Conectate TURN UDP'
  sudo ufw allow 49160:49200/udp comment 'Conectate TURN relay'
fi

echo "TURN_CONFIGURED=true"
echo "TURN_HOST=$TURN_HOST_INPUT"
echo "TURN_PUBLIC_IP=$public_ip"
echo "TURN_LOCAL_IP=$local_ip"
echo "TURN_CREDENTIALS_HTTP=$code"
echo "ROUTER_REQUIRED=Reenvía TCP+UDP 3478 y UDP 49160-49200 hacia $local_ip"
echo "CGNAT_LIMITATION=Si tu ISP usa CGNAT y no puedes abrir esos puertos, este coturn local no será alcanzable por usuarios públicos; usa TURN_STATIC_URLS o coturn en una VPS."
