#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="${ROOT_DIR:-$HOME/conectate-local-fallback}"
SIGNALING_DIR="${SIGNALING_DIR:-$HOME/signaling-server}"

echo "=== CONECTATE DRIFT AUDIT ==="
date -Is
echo

echo "=== HOST ==="
hostname
uptime
hostname -I || true
echo

echo "=== DIRECTORIOS ==="
for dir in "$ROOT_DIR" "$SIGNALING_DIR"; do
  echo "--- $dir"
  if [[ -d "$dir" ]]; then
    find "$dir" -maxdepth 2 -type f \
      \( -name '*.js' -o -name '*.json' -o -name '*.yml' -o -name '*.yaml' -o -name 'Dockerfile' -o -name '.conectate-release' \) \
      -printf '%TY-%Tm-%Td %TH:%TM:%TS %p\n' 2>/dev/null | sort | tail -80
  else
    echo "MISSING"
  fi
done
echo

echo "=== RELEASE MARKER ==="
cat "$ROOT_DIR/.conectate-release" 2>/dev/null || echo "NO_RELEASE_MARKER"
echo

echo "=== SYSTEMD STACK ==="
systemctl cat conectate-stack.service --no-pager || true
systemctl status conectate-stack.service --no-pager || true
echo

echo "=== TIMERS QUE PODRIAN CAMBIAR ESTADO ==="
systemctl list-timers --all --no-pager | grep -Ei 'conectate|backup|restore|sync|health|NEXT' || true
echo

echo "=== ENV PUBLICA SIN SECRETOS ==="
if [[ -f "$ROOT_DIR/.env" ]]; then
  grep -E '^(PUBLIC_BASE_URL|FALLBACK_EDGE_PORT|SIGNALING_CONTEXT|DUCKDNS_DOMAIN|TURN_|STUN_)=' "$ROOT_DIR/.env" \
    | sed -E 's/(TOKEN|SECRET|PASSWORD|CREDENTIAL)=.*/\1=***REDACTED***/g' || true
else
  echo "NO_ENV"
fi
echo

echo "=== DOCKER COMPOSE CONFIG HASH ==="
cd "$ROOT_DIR"
docker compose config 2>/dev/null | sha256sum || true
echo

echo "=== IMAGENES Y CONTENEDORES ==="
docker compose ps || true
echo
docker images --digests | grep -E 'conectate-local-fallback|postgres|nginx|coturn' || true
echo
for c in conectate-local-fallback-api-main conectate-local-fallback-signaling-main conectate-local-fallback-realtime-main conectate-local-fallback-edge-main; do
  echo "--- $c"
  docker inspect "$c" --format 'image={{.Image}} created={{.Created}} status={{.State.Status}} started={{.State.StartedAt}}' 2>/dev/null || true
done
echo

echo "=== HEALTH ==="
"$HOME/bin/conectate-status" || true
echo

echo "=== ULTIMOS LOGS STACK/HEALTH/RESTORE ==="
journalctl -u conectate-stack.service -u conectate-health.service -u conectate-restore-drill.service -u conectate-backup.service --since '24 hours ago' --no-pager | tail -260 || true

