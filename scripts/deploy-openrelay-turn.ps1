param(
  [string]$HostName = "100.91.84.2",
  [string]$User = "alvaro",
  [string]$KeyPath = "$env:USERPROFILE\.ssh\conectate_mini_ed25519"
)

$ErrorActionPreference = "Stop"

Write-Host "==> Copiando configurador OpenRelay TURN a $User@$HostName"
scp -i $KeyPath -o ConnectTimeout=10 ".\scripts\configure-openrelay-turn.sh" "${User}@${HostName}:~/configure-openrelay-turn.sh"

Write-Host "==> Ejecuta esto por SSH cuando quieras aplicarlo:"
Write-Host "ssh -i `"$KeyPath`" ${User}@${HostName}"
Write-Host "bash ~/configure-openrelay-turn.sh"
