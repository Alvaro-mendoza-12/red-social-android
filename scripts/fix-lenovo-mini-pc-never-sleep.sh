#!/usr/bin/env bash
set -Eeuo pipefail

echo "==> Conectate Lenovo/AMD mini PC: hard 24/7 no-sleep/no-freeze profile"
echo "Este script endurece energia, red, USB y kernel para servidor casero 24/7."
echo

if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  echo "ERROR: ejecútalo como usuario normal con sudo disponible, no como root directo." >&2
  exit 1
fi

PROJECT_DIR="${PROJECT_DIR:-$HOME/conectate-local-fallback}"
if [[ ! -d "$PROJECT_DIR" ]]; then
  echo "WARN: No existe $PROJECT_DIR; continúo con hardening del sistema."
fi

echo "==> 1) Bloqueando suspensión/hibernación systemd"
sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target

echo "==> 2) logind modo servidor sin monitor"
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

echo "==> 3) sleep.conf: no permitir sleep"
sudo mkdir -p /etc/systemd/sleep.conf.d
sudo tee /etc/systemd/sleep.conf.d/99-conectate-disable-sleep.conf >/dev/null <<'EOF'
[Sleep]
AllowSuspend=no
AllowHibernation=no
AllowSuspendThenHibernate=no
AllowHybridSleep=no
EOF

echo "==> 4) Inhibidor permanente de sleep idle"
sudo tee /etc/systemd/system/conectate-inhibit-sleep.service >/dev/null <<'EOF'
[Unit]
Description=Conectate hard block sleep/shutdown idle inhibitor
After=multi-user.target

[Service]
Type=simple
ExecStart=/usr/bin/systemd-inhibit --what=sleep:idle --mode=block --why=Conectate-production-server-24-7 /usr/bin/sleep infinity
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

echo "==> 5) Consola sin blank/powerdown"
sudo mkdir -p /etc/systemd/system/getty@tty1.service.d
sudo tee /etc/systemd/system/getty@tty1.service.d/99-conectate-no-blank.conf >/dev/null <<'EOF'
[Service]
TTYVTDisallocate=no
ExecStartPre=/usr/bin/setterm -blank 0 -powerdown 0 -powersave off
EOF

echo "==> 6) Kernel watchdog"
sudo mkdir -p /etc/systemd/system.conf.d
sudo tee /etc/systemd/system.conf.d/99-conectate-watchdog.conf >/dev/null <<'EOF'
[Manager]
RuntimeWatchdogSec=30s
RebootWatchdogSec=10min
EOF

echo "==> 7) Kernel flags para Lenovo/AMD + USB/NIC estable"
if [[ -f /etc/default/grub ]]; then
  sudo cp /etc/default/grub "/etc/default/grub.bak.conectate.$(date +%Y%m%d%H%M%S)"
  current="$(grep -E '^GRUB_CMDLINE_LINUX_DEFAULT=' /etc/default/grub | sed -E 's/^GRUB_CMDLINE_LINUX_DEFAULT="(.*)"$/\1/' || true)"
  wanted="$current"
  # irqpoll: mitiga bloqueos por IRQ "nobody cared" observados en algunos Lenovo/AMD.
  # usbcore.autosuspend=-1: evita que discos SATA-USB/NIC USB se duerman.
  # pcie_aspm=off: evita ahorro agresivo PCIe que puede dormir NIC/controladores.
  # processor.max_cstate=1 + idle=nomwait: evita freezes por estados profundos C-state en AMD antiguos.
  for flag in irqpoll usbcore.autosuspend=-1 pcie_aspm=off processor.max_cstate=1 idle=nomwait; do
    if [[ " $wanted " != *" $flag "* ]]; then
      wanted="$wanted $flag"
    fi
  done
  wanted="$(echo "$wanted" | xargs)"
  sudo sed -i -E "s|^GRUB_CMDLINE_LINUX_DEFAULT=.*|GRUB_CMDLINE_LINUX_DEFAULT=\"$wanted\"|" /etc/default/grub
  sudo update-grub || true
fi

echo "==> 8) Udev: USB y discos siempre encendidos"
sudo mkdir -p /etc/udev/rules.d
sudo tee /etc/udev/rules.d/99-conectate-no-usb-autosuspend.rules >/dev/null <<'EOF'
ACTION=="add", SUBSYSTEM=="usb", TEST=="power/control", ATTR{power/control}="on"
ACTION=="add", SUBSYSTEM=="scsi_disk", TEST=="manage_start_stop", ATTR{manage_start_stop}="0"
ACTION=="add", SUBSYSTEM=="block", KERNEL=="sd[a-z]", TEST=="queue/rotational", ATTR{queue/rotational}=="1", RUN+="/bin/sh -c 'echo 0 > /sys$devpath/device/power/autosuspend_delay_ms 2>/dev/null || true'"
EOF
sudo udevadm control --reload-rules || true
sudo udevadm trigger || true
for f in /sys/bus/usb/devices/*/power/control; do
  [[ -e "$f" ]] || continue
  echo on | sudo tee "$f" >/dev/null || true
done

echo "==> 9) Ethernet: Wake-on-LAN y sin autosuspend de dispositivo"
IFACE="$(ip -o -4 route show to default | awk '{print $5; exit}' || true)"
if [[ -n "${IFACE:-}" ]]; then
  if command -v ethtool >/dev/null 2>&1; then
    sudo ethtool -s "$IFACE" wol g || true
  fi
  if [[ -e "/sys/class/net/$IFACE/device/power/control" ]]; then
    echo on | sudo tee "/sys/class/net/$IFACE/device/power/control" >/dev/null || true
  fi
fi

echo "==> 10) Servicios Conectate al arranque"
sudo systemctl daemon-reload
sudo systemctl enable --now conectate-inhibit-sleep.service
sudo systemctl enable --now docker || true
sudo systemctl enable --now conectate-stack.service conectate-health.timer conectate-backup.timer || true
sudo loginctl enable-linger "$USER" || true
systemctl --user daemon-reload || true
systemctl --user enable --now conectate-cloudflare-tunnel.service || true

echo "==> 11) Comandos de diagnóstico"
mkdir -p "$HOME/bin"
install -m 0755 "$HOME/diagnose-mini-pc-power.sh" "$HOME/bin/conectate-power-diagnose" 2>/dev/null || true

echo "==> 12) Reiniciando logind"
sudo systemctl restart systemd-logind

echo
echo "HARDENING_APPLIED=true"
echo "REBOOT_REQUIRED=true"
echo
echo "IMPORTANTE: reinicia una vez para que los flags de kernel entren en vigor:"
echo "  sudo reboot"
echo
echo "Después de volver:"
echo "  ~/bin/conectate-status"
echo "  ~/bin/conectate-power-diagnose"
