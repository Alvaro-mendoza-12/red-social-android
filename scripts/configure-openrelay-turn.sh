#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-$HOME/conectate-local-fallback}"
ENV_FILE="$PROJECT_DIR/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: no existe $ENV_FILE"
  exit 1
fi

cd "$PROJECT_DIR"
cp "$ENV_FILE" "$ENV_FILE.bak.openrelay-$(date +%Y%m%dT%H%M%S)"

set_env() {
  local key="$1"
  local value="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
  else
    printf '\n%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

# OpenRelay/Metered public TURN fallback.
# This is not meant to replace a private production TURN/SFU forever,
# but it is the most practical no-cost bridge while the server is behind CGNAT.
set_env STUN_STATIC_URLS "stun:openrelay.metered.ca:80,stun:openrelay.metered.ca:443"
set_env TURN_STATIC_URLS "turn:openrelay.metered.ca:80,turn:openrelay.metered.ca:443,turn:openrelay.metered.ca:443?transport=tcp,turns:openrelay.metered.ca:443?transport=tcp"
set_env TURN_STATIC_USERNAME "openrelayproject"
set_env TURN_STATIC_CREDENTIAL "openrelayproject"
set_env TURN_REALM "openrelay.metered.ca"

echo "==> Reiniciando signaling para aplicar ICE/TURN externo..."
docker compose --profile turn up -d signaling edge

echo "==> Probando endpoint local /turn-credentials..."
sleep 3
turn_http_code="$(curl -sS -o /tmp/conectate-turn-credentials-check.json -w '%{http_code}' http://127.0.0.1:3080/turn-credentials || true)"
case "$turn_http_code" in
  200)
    cat /tmp/conectate-turn-credentials-check.json
    echo
    ;;
  401|403)
    echo "TURN_ENDPOINT_AUTH_OK=true"
    echo "INFO: /turn-credentials respondió HTTP $turn_http_code. Eso es correcto si exige Authorization; la app lo consumirá con sesión iniciada."
    ;;
  *)
    echo
    echo "WARN: /turn-credentials no respondió como se esperaba (HTTP $turn_http_code). Revisa logs:"
    cat /tmp/conectate-turn-credentials-check.json 2>/dev/null || true
    docker logs --tail=80 conectate-local-fallback-signaling-main
    exit 1
    ;;
esac
echo "OPENRELAY_TURN_CONFIGURED=true"
