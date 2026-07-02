#!/usr/bin/env bash
# Deja la mini PC como servidor 24/7: sin suspensión, servicios al arranque
# y comandos simples para revisar/reparar Conectate.
set -Eeuo pipefail

if [ "${EUID:-$(id -u)}" -eq 0 ]; then
  echo "Ejecuta como usuario normal (alvaro), no como root."
  exit 1
fi

RUN_USER="$(id -un)"
ROOT_DIR="${ROOT_DIR:-$HOME/conectate-local-fallback}"

if [ ! -d "$ROOT_DIR" ]; then
  echo "ERROR: No existe $ROOT_DIR"
  exit 1
fi

echo "==> Bloqueando suspension/hibernacion"
if sudo -n true 2>/dev/null; then
  sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target >/dev/null 2>&1 || true

  echo "==> Configurando logind para modo servidor"
  sudo mkdir -p /etc/systemd/logind.conf.d
  sudo tee /etc/systemd/logind.conf.d/99-conectate-server.conf >/dev/null <<'EOF'
[Login]
IdleAction=ignore
HandleSuspendKey=ignore
HandleHibernateKey=ignore
HandleLidSwitch=ignore
HandleLidSwitchExternalPower=ignore
HandleLidSwitchDocked=ignore
EOF
else
  echo "WARN: sudo requiere contraseña interactiva; omito ajustes globales de energia."
fi

echo "==> Activando Docker y stack al arranque"
if sudo -n true 2>/dev/null; then
  sudo systemctl enable --now docker >/dev/null
  sudo systemctl enable --now conectate-stack.service conectate-backup.timer conectate-health.timer >/dev/null

  if systemctl list-unit-files | grep -q '^conectate-restore-drill.timer'; then
    sudo systemctl enable --now conectate-restore-drill.timer >/dev/null || true
  fi
else
  echo "WARN: sudo requiere contraseña interactiva; no cambio servicios globales."
fi

echo "==> Permitiendo servicios de usuario sin sesion abierta"
sudo loginctl enable-linger "$RUN_USER" >/dev/null 2>&1 || true
loginctl enable-linger "$RUN_USER" >/dev/null 2>&1 || true

echo "==> Activando tunnel Cloudflare al arranque"
systemctl --user daemon-reload
systemctl --user enable --now conectate-cloudflare-tunnel.service >/dev/null

echo "==> Instalando comandos de operacion"
mkdir -p "$HOME/bin"
cat > "$HOME/bin/conectate-status" <<'EOF'
#!/usr/bin/env bash
set -u
cd "$HOME/conectate-local-fallback" 2>/dev/null || {
  echo "ERROR: no existe ~/conectate-local-fallback"
  exit 1
}
echo "=== SISTEMA ==="
hostname
uptime
echo
echo "=== SERVICIOS ==="
systemctl is-active docker | sed 's/^/docker=/'
systemctl is-active conectate-stack.service | sed 's/^/stack=/'
systemctl --user is-active conectate-cloudflare-tunnel.service | sed 's/^/cloudflare=/'
echo
echo "=== CONTENEDORES ==="
docker compose --profile turn ps
echo
echo "=== URL PUBLICA ==="
cat "$HOME/conectate-public-url.txt" 2>/dev/null || true
echo
echo "=== READY LOCAL ==="
curl -fsS http://127.0.0.1:3080/ready || true
echo
echo "=== READY PUBLIC ==="
url="$(cat "$HOME/conectate-public-url.txt" 2>/dev/null || true)"
if [ -n "$url" ]; then
  curl -fsS "${url%/}/ready" || true
fi
echo
echo "=== TIMERS ==="
systemctl list-timers --all --no-pager | grep -E 'conectate|NEXT' || true
EOF

cat > "$HOME/bin/conectate-restart" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
cd "$HOME/conectate-local-fallback"
echo "==> Reiniciando stack Docker"
sudo systemctl restart conectate-stack.service
echo "==> Reiniciando tunnel Cloudflare"
systemctl --user restart conectate-cloudflare-tunnel.service
echo "==> Esperando salud local"
for _ in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:3080/ready >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
echo "==> Estado final"
conectate-status
EOF

cat > "$HOME/bin/conectate-url" <<'EOF'
#!/usr/bin/env bash
cat "$HOME/conectate-public-url.txt" 2>/dev/null || true
EOF

chmod +x "$HOME/bin/conectate-status" "$HOME/bin/conectate-restart" "$HOME/bin/conectate-url"

echo "==> Estado actual"
"$HOME/bin/conectate-status"

echo
echo "MINI_PC_24_7_OK=true"
echo "Comandos:"
echo "  conectate-status"
echo "  conectate-restart"
echo "  conectate-url"
