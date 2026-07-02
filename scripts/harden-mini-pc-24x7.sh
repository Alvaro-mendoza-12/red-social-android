#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="${PROJECT_DIR:-$HOME/conectate-local-fallback}"
STATIC_IP="${STATIC_IP:-192.168.18.22}"
GATEWAY="${GATEWAY:-192.168.18.1}"
DNS_SERVERS="${DNS_SERVERS:-1.1.1.1,8.8.8.8}"

echo "==> Conectate 24/7 hardening"
echo "PROJECT_DIR=$PROJECT_DIR"
echo "STATIC_IP=$STATIC_IP"
echo "GATEWAY=$GATEWAY"
echo "DNS_SERVERS=$DNS_SERVERS"

if [[ ! -d "$PROJECT_DIR" ]]; then
  echo "ERROR: No existe PROJECT_DIR=$PROJECT_DIR" >&2
  exit 1
fi

echo "==> Bloqueando suspensión/hibernación a nivel systemd"
sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target

echo "==> Configurando logind para servidor sin monitor"
sudo mkdir -p /etc/systemd/logind.conf.d
sudo tee /etc/systemd/logind.conf.d/99-conectate-server.conf >/dev/null <<'EOF'
[Login]
IdleAction=ignore
IdleActionSec=0
HandlePowerKey=ignore
HandlePowerKeyLongPress=ignore
HandleSuspendKey=ignore
HandleSuspendKeyLongPress=ignore
HandleHibernateKey=ignore
HandleHibernateKeyLongPress=ignore
HandleLidSwitch=ignore
HandleLidSwitchExternalPower=ignore
HandleLidSwitchDocked=ignore
EOF

echo "==> Deshabilitando suspensión desde sleep.conf"
sudo mkdir -p /etc/systemd/sleep.conf.d
sudo tee /etc/systemd/sleep.conf.d/99-conectate-disable-sleep.conf >/dev/null <<'EOF'
[Sleep]
AllowSuspend=no
AllowHibernation=no
AllowSuspendThenHibernate=no
AllowHybridSleep=no
EOF

echo "==> Deshabilitando blank de consola"
sudo mkdir -p /etc/systemd/system/getty@tty1.service.d
sudo tee /etc/systemd/system/getty@tty1.service.d/99-conectate-no-blank.conf >/dev/null <<'EOF'
[Service]
TTYVTDisallocate=no
ExecStartPre=/usr/bin/setterm -blank 0 -powerdown 0 -powersave off
EOF

echo "==> Activando watchdog del kernel por systemd"
sudo mkdir -p /etc/systemd/system.conf.d
sudo tee /etc/systemd/system.conf.d/99-conectate-watchdog.conf >/dev/null <<'EOF'
[Manager]
RuntimeWatchdogSec=30s
RebootWatchdogSec=10min
EOF

echo "==> Aplicando estabilidad de kernel/USB para servidor 24/7"
# En esta mini PC Lenovo/AMD vimos en journal: "irq 7: nobody cared (try booting with \"irqpoll\")".
# Ese fallo no es suspensión normal: puede terminar en bloqueo duro. irqpoll es una mitigación
# conservadora para servidores pequeños. También desactivamos autosuspend USB porque los discos
# SATA-USB deben quedarse despiertos.
if [[ -f /etc/default/grub ]]; then
  sudo cp /etc/default/grub "/etc/default/grub.bak.$(date +%Y%m%d%H%M%S)"
  current_cmdline="$(grep -E '^GRUB_CMDLINE_LINUX_DEFAULT=' /etc/default/grub | sed -E 's/^GRUB_CMDLINE_LINUX_DEFAULT="(.*)"$/\1/' || true)"
  wanted_cmdline="$current_cmdline"
  for flag in irqpoll usbcore.autosuspend=-1; do
    if [[ " $wanted_cmdline " != *" $flag "* ]]; then
      wanted_cmdline="$wanted_cmdline $flag"
    fi
  done
  wanted_cmdline="$(echo "$wanted_cmdline" | xargs)"
  sudo sed -i -E "s|^GRUB_CMDLINE_LINUX_DEFAULT=.*|GRUB_CMDLINE_LINUX_DEFAULT=\"$wanted_cmdline\"|" /etc/default/grub
  if command -v update-grub >/dev/null 2>&1; then
    sudo update-grub || true
  fi
fi

sudo mkdir -p /etc/udev/rules.d
sudo tee /etc/udev/rules.d/99-conectate-no-usb-autosuspend.rules >/dev/null <<'EOF'
ACTION=="add", SUBSYSTEM=="usb", TEST=="power/control", ATTR{power/control}="on"
ACTION=="add", SUBSYSTEM=="scsi_disk", TEST=="manage_start_stop", ATTR{manage_start_stop}="0"
EOF
sudo udevadm control --reload-rules || true

echo "==> Habilitando Docker, stack, timers y Cloudflare tunnel"
sudo systemctl daemon-reload
sudo systemctl enable --now docker
sudo systemctl enable --now conectate-stack.service conectate-health.timer conectate-backup.timer
sudo loginctl enable-linger "$USER"
systemctl --user daemon-reload || true
systemctl --user enable --now conectate-cloudflare-tunnel.service || true
systemctl --user enable --now conectate-live-backend.service conectate-live-frontend.service conectate-live-cloudflare-tunnel.service 2>/dev/null || true

echo "==> Creando comando de recuperación rápida"
sudo tee /usr/local/bin/conectate-recover >/dev/null <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
cd "$HOME/conectate-local-fallback"
sudo systemctl restart docker
sudo systemctl restart conectate-stack.service
systemctl --user restart conectate-cloudflare-tunnel.service || true
systemctl --user restart conectate-live-backend.service conectate-live-frontend.service conectate-live-cloudflare-tunnel.service 2>/dev/null || true
sleep 10
"$HOME/bin/conectate-status" || true
if [[ -x "$HOME/bin/conectate-live-status" ]]; then
  "$HOME/bin/conectate-live-status" || true
fi
EOF
sudo chmod +x /usr/local/bin/conectate-recover

mkdir -p "$HOME/bin"
cat >"$HOME/bin/conectate-full-recover" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
/usr/local/bin/conectate-recover
EOF
chmod +x "$HOME/bin/conectate-full-recover"

echo "==> Configurando IP fija opcional en Netplan"
IFACE="$(ip -o -4 route show to default | awk '{print $5; exit}')"
if [[ -n "${IFACE:-}" ]]; then
  sudo mkdir -p /etc/netplan
  sudo tee /etc/netplan/99-conectate-static.yaml >/dev/null <<EOF
network:
  version: 2
  renderer: networkd
  ethernets:
    ${IFACE}:
      dhcp4: false
      addresses:
        - ${STATIC_IP}/24
      routes:
        - to: default
          via: ${GATEWAY}
      nameservers:
        addresses: [${DNS_SERVERS}]
EOF
  sudo chmod 600 /etc/netplan/99-conectate-static.yaml
  echo "Netplan preparado para $IFACE => $STATIC_IP. Se aplicará al final."
else
  echo "WARN: No pude detectar interfaz por defecto; salto IP fija."
fi

echo "==> Protegiendo red: desactivar powersave si NetworkManager existe"
if command -v nmcli >/dev/null 2>&1; then
  sudo mkdir -p /etc/NetworkManager/conf.d
  sudo tee /etc/NetworkManager/conf.d/99-conectate-no-powersave.conf >/dev/null <<'EOF'
[connection]
wifi.powersave = 2
ethernet.cloned-mac-address=preserve
EOF
  sudo systemctl restart NetworkManager || true
fi

echo "==> Intentando habilitar Wake-on-LAN en Ethernet"
if command -v ethtool >/dev/null 2>&1 && [[ -n "${IFACE:-}" ]]; then
  sudo ethtool -s "$IFACE" wol g || true
fi

echo "==> Reiniciando logind y aplicando red"
sudo systemctl restart systemd-logind
if [[ -f /etc/netplan/99-conectate-static.yaml ]]; then
  sudo netplan apply
fi

echo "==> Estado final"
"$HOME/bin/conectate-status" || true
echo
echo "OK: mini PC endurecida para 24/7. Si la BIOS apaga/suspende por firmware, configura:"
echo "  BIOS > Power: After Power Loss = Power On"
echo "  BIOS > Power: Wake on LAN = Enabled"
echo "  BIOS > Power/ACPI: desactivar Deep Sleep/ErP si aparece"
