param(
    [string]$User = "alvaro",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 2222
)

$ErrorActionPreference = "Stop"

$keyPath = Join-Path $env:USERPROFILE ".ssh\codex_conectate_vm"
if (-not (Test-Path $keyPath)) {
    throw "No existe la llave SSH $keyPath. Ejecuta primero scripts\authorize-vm-ssh.ps1"
}

$remoteScript = @'
set -euo pipefail

echo "==> Backup de netplan"
mkdir -p /root/conectate-network-backups
backup="/root/conectate-network-backups/netplan-$(date +%Y%m%d-%H%M%S).tar.gz"
tar -czf "$backup" -C /etc netplan
echo "Backup: $backup"

echo "==> Desactivando espera infinita de red en arranque"
systemctl disable --now systemd-networkd-wait-online.service >/dev/null 2>&1 || true
systemctl mask systemd-networkd-wait-online.service >/dev/null 2>&1 || true

echo "==> Escribiendo netplan para NAT + red administrativa host-only"
cat >/etc/netplan/99-conectate-vm.yaml <<'EOF'
network:
  version: 2
  renderer: networkd
  ethernets:
    enp0s3:
      dhcp4: true
      optional: true
    enp0s8:
      dhcp4: true
      optional: true
EOF
chmod 600 /etc/netplan/99-conectate-vm.yaml

echo "==> Aplicando red"
netplan generate
netplan apply

ip link set enp0s8 up >/dev/null 2>&1 || true
networkctl reload >/dev/null 2>&1 || true
networkctl reconfigure enp0s8 >/dev/null 2>&1 || true
networkctl renew enp0s8 >/dev/null 2>&1 || true

sleep 8

echo "==> Estado final"
hostname -I
ip -br addr
networkctl list || true
echo "==> IP publica"
curl -4 https://ifconfig.me || true
echo
'@

$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteScript))
$remote = "$User@$HostName"
$remoteCommand = "sudo bash -lc 'printf %s $encoded | base64 -d | bash'"

Write-Host "Configurando red de la VM por SSH en $remote puerto $Port"
Write-Host "Cuando pida password, escribe la contraseña de Ubuntu del usuario $User."
ssh -tt -i $keyPath -p $Port $remote $remoteCommand

Write-Host ""
Write-Host "Prueba post-configuracion:"
ssh -i $keyPath -p $Port -o BatchMode=yes $remote "hostname -I && ip -br addr"
