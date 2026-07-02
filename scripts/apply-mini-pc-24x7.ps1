param(
  [string]$HostName = "192.168.18.22",
  [string]$User = "alvaro",
  [string]$KeyPath = "$env:USERPROFILE\.ssh\conectate_mini_ed25519",
  [string]$StaticIp = "192.168.18.22",
  [string]$Gateway = "192.168.18.1"
)

$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "harden-mini-pc-24x7.sh"
if (!(Test-Path -LiteralPath $scriptPath)) {
  throw "No existe $scriptPath"
}

Write-Host "==> Verificando SSH $User@$HostName"
ssh -o ConnectTimeout=10 -i $KeyPath "$User@$HostName" "hostname; hostname -I"

Write-Host "==> Copiando hardening 24/7"
scp -i $KeyPath $scriptPath "$User@$HostName`:~/harden-mini-pc-24x7.sh"

Write-Host "==> Ejecutando hardening 24/7 en Ubuntu"
ssh -t -i $KeyPath "$User@$HostName" "chmod +x ~/harden-mini-pc-24x7.sh && STATIC_IP='$StaticIp' GATEWAY='$Gateway' ~/harden-mini-pc-24x7.sh"

Write-Host "==> Verificando /ready público/local"
ssh -i $KeyPath "$User@$StaticIp" "~/bin/conectate-status | sed -n '1,110p'"

Write-Host "OK: hardening aplicado."
