#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$HOME/conectate-local-fallback"
LOCAL_EDGE="${LOCAL_EDGE:-http://127.0.0.1:3080}"
BIN="$HOME/bin/cloudflared"
OWN_LOG="$HOME/cloudflared-conectate.log"
SYSTEM_LOG="$HOME/cloudflared.log"
URL_FILE="$HOME/conectate-public-url.txt"
PID_FILE="$HOME/.cache/conectate-cloudflare-tunnel.pid"
COMPOSE_LOCK="${CONECTATE_COMPOSE_LOCK:-$HOME/.cache/conectate-compose.lock}"
mkdir -p "$(dirname "$PID_FILE")"

env_value() {
  [ -f "$ROOT_DIR/.env" ] || return 0
  sed -n "s/^$1=//p" "$ROOT_DIR/.env" | tail -n 1
}

tunnel_ready() {
  local url="$1"
  curl -fsS --max-time 20 "${url%/}/ready" >/dev/null 2>&1
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

update_public_url() {
  local url="$1"
  local previous
  wait_stack_startup
  (
    flock --wait 240 9 || {
      echo 'ERROR: no se pudo adquirir el bloqueo de Docker Compose' >&2
      exit 1
    }
    previous="$(cat "$URL_FILE" 2>/dev/null || true)"
    if [ "$previous" != "$url" ]; then
      cd "$ROOT_DIR"
      if grep -q '^PUBLIC_BASE_URL=' .env 2>/dev/null; then
        sed -i "s|^PUBLIC_BASE_URL=.*|PUBLIC_BASE_URL=$url|" .env
      else
        printf 'PUBLIC_BASE_URL=%s\n' "$url" >> .env
      fi
      docker compose up -d --remove-orphans >/dev/null 2>&1
      wait_backend || {
        echo 'ERROR: la API no se recupero despues de actualizar PUBLIC_BASE_URL' >&2
        exit 1
      }
    fi
    echo "$url" > "$URL_FILE"
  ) 9>"$COMPOSE_LOCK"
  publish_duckdns_txt "$url" || true
  echo "PUBLIC_URL=$url"
}

system_tunnel_url() {
  systemctl is-active --quiet conectate-cloudflared.service 2>/dev/null || return 1
  pgrep -f '^/usr/local/bin/cloudflared tunnel .*127\.0\.0\.1:3080' >/dev/null 2>&1 || return 1
  local url
  url="$(grep -Eo 'https://[-a-zA-Z0-9.]+\.trycloudflare\.com' "$SYSTEM_LOG" 2>/dev/null | tail -1 || true)"
  [[ "$url" =~ ^https://[-a-zA-Z0-9.]+\.trycloudflare\.com$ ]] || return 1
  tunnel_ready "$url" || return 1
  printf '%s\n' "$url"
}

own_pid() {
  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || return 1
  ps -p "$pid" -o args= 2>/dev/null | grep -F -- "--url $LOCAL_EDGE" >/dev/null 2>&1
  printf '%s\n' "$pid"
}

stop_own_tunnel() {
  local pid
  pid="$(own_pid || true)"
  if [ -n "$pid" ]; then
    kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 10); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -KILL "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
}

start_own_tunnel() {
  : > "$OWN_LOG"
  "$BIN" tunnel --no-autoupdate --url "$LOCAL_EDGE" >>"$OWN_LOG" 2>&1 &
  echo "$!" > "$PID_FILE"
}

own_tunnel_url() {
  own_pid >/dev/null || return 1
  local url
  url="$(grep -Eo 'https://[-a-zA-Z0-9.]+\.trycloudflare\.com' "$OWN_LOG" 2>/dev/null | tail -1 || true)"
  [[ "$url" =~ ^https://[-a-zA-Z0-9.]+\.trycloudflare\.com$ ]] || return 1
  tunnel_ready "$url" || return 1
  printf '%s\n' "$url"
}

while true; do
  if ! wait_backend; then
    echo 'WARN: backend local no listo; reintentando en 30s'
    sleep 30
    continue
  fi

  if public_url="$(system_tunnel_url)"; then
    stop_own_tunnel
    update_public_url "$public_url"
    sleep 90
    continue
  fi

  if public_url="$(own_tunnel_url)"; then
    update_public_url "$public_url"
    sleep 90
    continue
  fi

  stop_own_tunnel
  start_own_tunnel
  public_url=''
  for _ in $(seq 1 60); do
    public_url="$(own_tunnel_url || true)"
    [ -n "$public_url" ] && break
    sleep 2
  done
  if [ -z "$public_url" ]; then
    echo 'WARN: no se pudo abrir el túnel de respaldo; reintentando'
    stop_own_tunnel
    sleep 20
    continue
  fi
  update_public_url "$public_url"
  sleep 90
done
