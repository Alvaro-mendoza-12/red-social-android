#!/usr/bin/env bash
# Instala cloudflared Quick Tunnel en la mini PC para produccion detras de CGNAT.
# No requiere abrir puertos 80/443 en el router.
set -Eeuo pipefail

if [ "${EUID:-$(id -u)}" -eq 0 ]; then
  echo "Ejecuta como usuario normal (alvaro), no como root."
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_USER="$(id -un)"
LOCAL_EDGE="${LOCAL_EDGE:-http://127.0.0.1:3080}"
BIN_DIR="$HOME/bin"
LOG_FILE="$HOME/cloudflared-conectate.log"
URL_FILE="$HOME/conectate-public-url.txt"
SYSTEMD_DIR="$HOME/.config/systemd/user"

mkdir -p "$BIN_DIR" "$SYSTEMD_DIR"

if [ ! -x "$BIN_DIR/cloudflared" ]; then
  echo "==> Descargando cloudflared"
  curl -fsSL --retry 3 \
    -o "$BIN_DIR/cloudflared" \
    "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"
  chmod +x "$BIN_DIR/cloudflared"
fi

cat > "$BIN_DIR/conectate-cloudflare-tunnel.sh" <<'SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$HOME/conectate-local-fallback"
LOCAL_EDGE="${LOCAL_EDGE:-http://127.0.0.1:3080}"
BIN="$HOME/bin/cloudflared"
LOG="$HOME/cloudflared-conectate.log"
URL_FILE="$HOME/conectate-public-url.txt"
COMPOSE_LOCK="${CONECTATE_COMPOSE_LOCK:-$HOME/.cache/conectate-compose.lock}"
mkdir -p "$(dirname "$COMPOSE_LOCK")"

env_value() {
  [ -f "$ROOT_DIR/.env" ] || return 0
  sed -n "s/^$1=//p" "$ROOT_DIR/.env" | tail -n 1
}

update_public_base_url() {
  local url="$1"
  [ -n "$url" ] || return 1
  (
    flock --wait 240 9 || exit 1
    cd "$ROOT_DIR"
    if grep -q '^PUBLIC_BASE_URL=' .env 2>/dev/null; then
      sed -i "s|^PUBLIC_BASE_URL=.*|PUBLIC_BASE_URL=$url|" .env
    else
      printf 'PUBLIC_BASE_URL=%s\n' "$url" >> .env
    fi
    docker compose up -d --remove-orphans >/dev/null 2>&1
    wait_backend
    echo "$url" > "$URL_FILE"
  ) 9>"$COMPOSE_LOCK"
}

publish_duckdns_txt() {
  local url="$1"
  local domain token
  domain="$(env_value DUCKDNS_DOMAIN)"
  token="$(env_value DUCKDNS_TOKEN)"
  [ -n "$domain" ] && [ -n "$token" ] || return 0
  domain="${domain%.duckdns.org}"
  curl -fsS --max-time 20 \
    "https://www.duckdns.org/update?domains=${domain}&token=${token}&txt=${url}&verbose=true" \
    | grep -q '^OK' && echo "DUCKDNS_TXT_OK=$url"
}

wait_backend() {
  for _ in $(seq 1 90); do
    curl -fsS --max-time 5 "${LOCAL_EDGE%/}/ready" >/dev/null 2>&1 && return 0
    sleep 2
  done
  return 1
}

wait_stack_startup() {
  local state uptime_seconds
  for _ in $(seq 1 120); do
    state="$(systemctl show conectate-stack.service -p ActiveState --value 2>/dev/null || true)"
    case "$state" in
      activating|reloading|deactivating)
        sleep 2
        ;;
      inactive)
        uptime_seconds="$(cut -d. -f1 /proc/uptime 2>/dev/null || echo 999)"
        if [ "${uptime_seconds:-999}" -lt 180 ]; then
          sleep 2
        else
          return 0
        fi
        ;;
      *)
        return 0
        ;;
    esac
  done
  echo 'WARN: conectate-stack no termino de activar; continuando con recuperacion del tunel' >&2
}

extract_tunnel_url() {
  grep -Eo 'https://[-a-zA-Z0-9.]+\.trycloudflare\.com' "$LOG" | tail -1
}

tunnel_ready() {
  local url="$1"
  curl -fsS --max-time 20 "${url%/}/ready" >/dev/null 2>&1
}

wait_tunnel_ready() {
  local url="$1"
  local attempt
  for attempt in $(seq 1 60); do
    if tunnel_ready "$url"; then
      return 0
    fi
    sleep 3
  done
  return 1
}

stop_cloudflared() {
  pkill -TERM -x cloudflared >/dev/null 2>&1 || true
  for _ in $(seq 1 10); do
    pgrep -x cloudflared >/dev/null 2>&1 || return 0
    sleep 1
  done
  pkill -KILL -x cloudflared >/dev/null 2>&1 || true
}

while true; do
  if ! wait_backend; then
    echo "WARN: backend local no listo; reintentando en 30s"
    sleep 30
    continue
  fi

  wait_stack_startup
  existing_url="$(cat "$URL_FILE" 2>/dev/null || true)"
  if pgrep -x cloudflared >/dev/null 2>&1 \
    && [[ "$existing_url" =~ ^https://[-a-zA-Z0-9.]+\.trycloudflare\.com$ ]] \
    && tunnel_ready "$existing_url"; then
    sleep 90
    continue
  fi

  stop_cloudflared
  : > "$LOG"

  "$BIN" tunnel --no-autoupdate --url "$LOCAL_EDGE" >>"$LOG" 2>&1 &
  public_url=""
  for _ in $(seq 1 60); do
    public_url="$(extract_tunnel_url || true)"
    [ -n "$public_url" ] && break
    sleep 2
  done

  if [ -z "$public_url" ]; then
    echo "ERROR: cloudflared no entrego URL. Log:"
    tail -n 40 "$LOG" || true
    stop_cloudflared
    sleep 30
    continue
  fi

  if ! wait_tunnel_ready "$public_url"; then
    echo "WARN: tunel aun no responde /ready; reintentando sin cambiar URL"
    stop_cloudflared
    sleep 20
    continue
  fi

  update_public_base_url "$public_url"
  publish_duckdns_txt "$public_url" || true
  echo "PUBLIC_URL=$public_url"

  fail_streak=0
  while pgrep -x cloudflared >/dev/null 2>&1; do
    if tunnel_ready "$public_url"; then
      fail_streak=0
      sleep 90
      continue
    fi
    fail_streak=$((fail_streak + 1))
    echo "WARN: chequeo remoto fallo ($fail_streak/5)"
    if [ "$fail_streak" -ge 5 ]; then
      echo "WARN: tunel degradado tras 5 fallos; reiniciando"
      break
    fi
    sleep 60
  done
done
SCRIPT

chmod +x "$BIN_DIR/conectate-cloudflare-tunnel.sh"

# Keep the runtime script versioned. The heredoc above remains only for
# compatibility with older deployment bundles that do not include this file.
if [ -f "$ROOT_DIR/scripts/conectate-cloudflare-tunnel.sh" ]; then
  install -m 755 \
    "$ROOT_DIR/scripts/conectate-cloudflare-tunnel.sh" \
    "$BIN_DIR/conectate-cloudflare-tunnel.sh"
fi

cat > "$SYSTEMD_DIR/conectate-cloudflare-tunnel.service" <<EOF
[Unit]
Description=Conectate Cloudflare Quick Tunnel (CGNAT)
After=network-online.target conectate-stack.service
Wants=network-online.target

[Service]
Type=simple
ExecStart=$BIN_DIR/conectate-cloudflare-tunnel.sh
Restart=always
RestartSec=15
Environment=LOCAL_EDGE=$LOCAL_EDGE

[Install]
WantedBy=default.target
EOF

loginctl enable-linger "$RUN_USER" 2>/dev/null || true
systemctl --user daemon-reload
systemctl --user enable --now conectate-cloudflare-tunnel.service
systemctl --user restart conectate-cloudflare-tunnel.service

echo "CLOUDFLARE_TUNNEL_SERVICE=enabled"
echo "URL_FILE=$URL_FILE"
echo "LOG_FILE=$LOG_FILE"
echo ""
echo "Opcional: agrega a $ROOT_DIR/.env para que la APK descubra la URL sin Windows:"
echo "  DUCKDNS_DOMAIN=conectate-alvaro.duckdns.org"
echo "  DUCKDNS_TOKEN=tu_token_duckdns"
echo ""
echo "Comprueba:"
echo "  systemctl --user status conectate-cloudflare-tunnel.service --no-pager"
echo "  cat $URL_FILE"
echo "  curl -fsS \"\$(cat $URL_FILE)/ready\""
