#!/usr/bin/env bash
set -euo pipefail

PUBLIC_DOMAIN="${PUBLIC_DOMAIN:-}"
DUCKDNS_TOKEN="${DUCKDNS_TOKEN:-}"
EDGE_PORT="${EDGE_PORT:-3080}"
ACME_EMAIL="${ACME_EMAIL:-}"
MAX_BODY_SIZE="${MAX_BODY_SIZE:-1024MB}"

if [ -z "$PUBLIC_DOMAIN" ]; then
  echo "ERROR: falta PUBLIC_DOMAIN. Ejemplo:" >&2
  echo "PUBLIC_DOMAIN=conectate-demo.duckdns.org DUCKDNS_TOKEN=tu-token bash scripts/setup-public-duckdns-caddy.sh" >&2
  exit 1
fi

PUBLIC_DOMAIN="${PUBLIC_DOMAIN#https://}"
PUBLIC_DOMAIN="${PUBLIC_DOMAIN#http://}"
PUBLIC_DOMAIN="${PUBLIC_DOMAIN%/}"
PUBLIC_BASE_URL="https://${PUBLIC_DOMAIN}"

if [ ! -f docker-compose.yml ]; then
  echo "ERROR: ejecuta este script desde ~/conectate-local-fallback" >&2
  exit 1
fi

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    return 1
  fi
}

set_env_value() {
  key="$1"
  value="$2"
  if [ -f .env ] && grep -qE "^${key}=" .env; then
    sed -i "s|^${key}=.*|${key}=${value}|" .env
  else
    printf '\n%s=%s\n' "$key" "$value" >> .env
  fi
}

echo "==> Preparando URL publica: ${PUBLIC_BASE_URL}"
set_env_value PUBLIC_BASE_URL "$PUBLIC_BASE_URL"
set_env_value FALLBACK_EDGE_PORT "$EDGE_PORT"

echo "==> Verificando Docker local"
docker compose up -d db api signaling realtime-gateway edge
for i in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${EDGE_PORT}/ready" >/dev/null 2>&1; then
    break
  fi
  if [ "$i" = "60" ]; then
    echo "ERROR: edge local no responde en http://127.0.0.1:${EDGE_PORT}/ready" >&2
    docker compose ps || true
    docker compose logs --tail=120 api edge signaling realtime-gateway || true
    exit 1
  fi
  sleep 2
done

echo "==> Instalando Caddy si hace falta"
if ! require_command caddy; then
  sudo apt-get update
  sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg
  if [ ! -f /usr/share/keyrings/caddy-stable-archive-keyring.gpg ]; then
    curl -fsSL "https://dl.cloudsmith.io/public/caddy/stable/gpg.key" \
      | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  fi
  if [ ! -f /etc/apt/sources.list.d/caddy-stable.list ]; then
    curl -fsSL "https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt" \
      | sudo tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
  fi
  sudo apt-get update
  sudo apt-get install -y caddy
fi

if require_command ufw && sudo ufw status 2>/dev/null | grep -qi "Status: active"; then
  echo "==> Abriendo firewall local para HTTP/HTTPS"
  sudo ufw allow 80/tcp
  sudo ufw allow 443/tcp
fi

echo "==> Escribiendo /etc/caddy/Caddyfile"
tmp_caddyfile="$(mktemp)"
{
  if [ -n "$ACME_EMAIL" ]; then
    printf '{\n  email %s\n}\n\n' "$ACME_EMAIL"
  fi
  cat <<EOF
${PUBLIC_DOMAIN} {
  encode zstd gzip

  request_body {
    max_size ${MAX_BODY_SIZE}
  }

  reverse_proxy 127.0.0.1:${EDGE_PORT}

  header {
    -Server
  }
}
EOF
} > "$tmp_caddyfile"
sudo install -m 0644 "$tmp_caddyfile" /etc/caddy/Caddyfile
rm -f "$tmp_caddyfile"

sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl enable caddy
sudo systemctl reload caddy || sudo systemctl restart caddy

if [ -n "$DUCKDNS_TOKEN" ]; then
  case "$PUBLIC_DOMAIN" in
    *.duckdns.org) ;;
    *)
      echo "ERROR: DUCKDNS_TOKEN solo debe usarse con un dominio *.duckdns.org" >&2
      exit 1
      ;;
  esac
  duck_domain="${PUBLIC_DOMAIN%.duckdns.org}"
  echo "==> Configurando DuckDNS para ${duck_domain}"
  mkdir -p "$HOME/duckdns"
  cat > "$HOME/duckdns/duck.sh" <<EOF
#!/usr/bin/env sh
curl -fsS "https://www.duckdns.org/update?domains=${duck_domain}&token=${DUCKDNS_TOKEN}&ip=" >/tmp/duckdns.log
EOF
  chmod 700 "$HOME/duckdns/duck.sh"
  "$HOME/duckdns/duck.sh" || {
    echo "ERROR: DuckDNS no acepto la actualizacion. Revisa DUCKDNS_TOKEN y PUBLIC_DOMAIN." >&2
    exit 1
  }
  current_cron="$(crontab -l 2>/dev/null || true)"
  cron_line="*/5 * * * * $HOME/duckdns/duck.sh >/dev/null 2>&1"
  if ! printf '%s\n' "$current_cron" | grep -Fq "$HOME/duckdns/duck.sh"; then
    (printf '%s\n' "$current_cron"; printf '%s\n' "$cron_line") | crontab -
  fi
fi

echo "==> Verificacion local"
curl -fsS "http://127.0.0.1:${EDGE_PORT}/ready"
echo

echo "==> Verificacion publica"
if curl -fsS "${PUBLIC_BASE_URL}/ready"; then
  echo
  echo "LISTO: ${PUBLIC_BASE_URL} ya responde desde Caddy."
else
  echo
  echo "ATENCION: Caddy local quedo configurado, pero la URL publica aun no responde."
  echo "Revisa que DuckDNS apunte a tu IP publica y que el router reenvie 80/443 hacia esta VM."
fi

echo "PUBLIC_BASE_URL=${PUBLIC_BASE_URL}"
