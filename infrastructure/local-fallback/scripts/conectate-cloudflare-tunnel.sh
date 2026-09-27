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
TUNNEL_HEALTH_PROBES="${TUNNEL_HEALTH_PROBES:-3}"
TUNNEL_HEALTH_REQUIRED_SUCCESSES="${TUNNEL_HEALTH_REQUIRED_SUCCESSES:-2}"
TUNNEL_HEALTH_TIMEOUT_SECONDS="${TUNNEL_HEALTH_TIMEOUT_SECONDS:-8}"
mkdir -p "$(dirname "$PID_FILE")"

env_value() {
  [ -f "$ROOT_DIR/.env" ] || return 0
  sed -n "s/^$1=//p" "$ROOT_DIR/.env" | tail -n 1
}

set_env_value() {
  local key="$1" value="$2"
  if grep -q "^${key}=" "$ROOT_DIR/.env" 2>/dev/null; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$ROOT_DIR/.env"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ROOT_DIR/.env"
  fi
}

tunnel_ready() {
  local url="$1" successes=0
  local probe
  for probe in $(seq 1 "$TUNNEL_HEALTH_PROBES"); do
    if curl -4 -fsS --max-time "$TUNNEL_HEALTH_TIMEOUT_SECONDS" \
      "${url%/}/ready" >/dev/null 2>&1; then
      successes=$((successes + 1))
    fi
    [ "$probe" -lt "$TUNNEL_HEALTH_PROBES" ] && sleep 1
  done
  [ "$successes" -ge "$TUNNEL_HEALTH_REQUIRED_SUCCESSES" ]
}

wait_backend() {
  for _ in $(seq 1 90); do
    curl -fsS --max-time 5 "${LOCAL_EDGE%/}/ready" >/dev/null 2>&1 && return 0
    sleep 2
  done
  return 1
}

reconcile_stack_public_url() {
  cd "$ROOT_DIR"
  docker compose up -d --remove-orphans >/dev/null 2>&1 || return 1
  # Nginx resolves Compose service names when its workers start. Recreating
  # api/realtime/signaling without reloading edge leaves stale container IPs
  # and turns an otherwise healthy tunnel into a persistent 502.
  docker compose restart edge >/dev/null 2>&1 || return 1
  wait_backend
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
  local domain token private_key ttl envelope
  domain="$(env_value DUCKDNS_DOMAIN)"
  token="$(env_value DUCKDNS_TOKEN)"
  [ -n "$domain" ] && [ -n "$token" ] || return 0
  private_key="$(env_value BACKEND_DISCOVERY_PRIVATE_KEY_FILE)"
  ttl="$(env_value BACKEND_DISCOVERY_TTL_SECONDS)"
  ttl="${ttl:-86400}"
  if [ -z "$private_key" ] || [ ! -r "$private_key" ]; then
    echo 'ERROR: falta BACKEND_DISCOVERY_PRIVATE_KEY_FILE legible; no se publico un TXT inseguro' >&2
    return 1
  fi
  envelope="$(node "$ROOT_DIR/scripts/backend-discovery-envelope.mjs" sign \
    --private-key "$private_key" --url "$url" --ttl-seconds "$ttl")" || return 1
  [[ "$envelope" == novvin1:* ]] || return 1
  domain="${domain%.duckdns.org}"
  curl -fsS --max-time 20 \
    "https://www.duckdns.org/update?domains=${domain}&token=${token}&txt=${envelope}&verbose=true" \
    | grep -q '^OK' && echo "DUCKDNS_SIGNED_TXT_OK=${domain}.duckdns.org"
}

update_public_url() {
  local url="$1"
  local previous_env advertised_url stable_url
  wait_stack_startup
  (
    flock --wait 240 9 || {
      echo 'ERROR: no se pudo adquirir el bloqueo de Docker Compose' >&2
      exit 1
    }
    stable_url="$(env_value STABLE_PUBLIC_BASE_URL)"
    advertised_url="$url"
    # Funnel accepts HTTPS on both 443 and 8443. Prefer the standard 443
    # origin when configured so mobile networks that block 8443 can log in.
    if [[ "$stable_url" =~ ^https://[-a-zA-Z0-9.]+\.ts\.net(:8443)?$ ]] && tunnel_ready "$stable_url"; then
      advertised_url="$stable_url"
    elif [ -n "$stable_url" ]; then
      echo 'WARN: origen estable no disponible; usando temporalmente el túnel de respaldo' >&2
    fi
    previous_env="$(env_value PUBLIC_BASE_URL)"
    if [ "$previous_env" != "$advertised_url" ]; then
      cd "$ROOT_DIR"
      set_env_value PUBLIC_BASE_URL "$advertised_url"
      if ! reconcile_stack_public_url; then
        echo 'ERROR: el stack no se recupero despues de actualizar PUBLIC_BASE_URL; revirtiendo' >&2
        if [ -n "$previous_env" ]; then
          set_env_value PUBLIC_BASE_URL "$previous_env"
          reconcile_stack_public_url || \
            echo 'ERROR: el stack tampoco se recupero despues del rollback' >&2
        fi
        exit 1
      fi
    fi
    echo "$url" > "$URL_FILE"
  ) 9>"$COMPOSE_LOCK"
  advertised_url="$(env_value PUBLIC_BASE_URL)"
  publish_duckdns_txt "$advertised_url" || echo 'WARN: el endpoint funciona, pero la rotacion firmada no se publico' >&2
  echo "PUBLIC_URL=$advertised_url"
}

current_public_url() {
  local url
  url="$(cat "$URL_FILE" 2>/dev/null || true)"
  [[ "$url" =~ ^https://[-a-zA-Z0-9.]+\.trycloudflare\.com$ ]] || return 1

  # A living process may reconnect forever after its Quick Tunnel name was
  # removed. DNS agreement is a useful early signal, but it is not sufficient:
  # resolvers can briefly return unknown while the edge is unhealthy and a
  # stale process can still own the old log entry. Every cycle therefore needs
  # a bounded end-to-end /ready probe before the URL is retained. This keeps a
  # dead public hostname from being advertised indefinitely while preserving
  # the existing three-probe/ two-success tolerance in tunnel_ready(). If the connector that owns the published hostname is still
  # alive but /ready fails, the loop below rotates it instead of advertising
  # that stale process.
  if node "$ROOT_DIR/scripts/public-tunnel-dns.mjs" "$url" >/dev/null; then
    echo "PUBLIC_URL_DNS_REMOVED=$url" >&2
    return 1
  fi
  tunnel_ready "$url" || return 1
  printf '%s\n' "$url"
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
  # This host has exhibited intermittent QUIC/IPv6 route loss. HTTP/2 over
  # IPv4 is slower on paper but materially more stable for long media ranges.
  "$BIN" tunnel --no-autoupdate --protocol http2 --edge-ip-version 4 \
    --url "$LOCAL_EDGE" >>"$OWN_LOG" 2>&1 &
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

  # Keep the published endpoint while it is healthy. Choosing a candidate
  # merely because it is also healthy caused a URL/container rotation every
  # cycle and interrupted active clients.
  if public_url="$(current_public_url)"; then
    update_public_url "$public_url"
    sleep 90
    continue
  fi

  if public_url="$(own_tunnel_url)"; then
    update_public_url "$public_url"
    sleep 90
    continue
  fi

  if public_url="$(system_tunnel_url)"; then
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
