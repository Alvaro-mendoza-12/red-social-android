param(
    [string]$User = "alvaro",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 2222
)

$ErrorActionPreference = "Stop"

$sshDir = Join-Path $env:USERPROFILE ".ssh"
$keyPath = Join-Path $sshDir "codex_conectate_vm"
$pubPath = "$keyPath.pub"

New-Item -ItemType Directory -Force -Path $sshDir | Out-Null

if (-not (Test-Path $keyPath)) {
    ssh-keygen -t ed25519 -f $keyPath -N "" -C "codex-conectate-vm" | Out-Null
}

$knownHost = "[$HostName]:$Port"
ssh-keygen -R $knownHost 2>$null | Out-Null

$remote = "$User@$HostName"
$publicKey = (Get-Content $pubPath -Raw).Trim()
$escapedPublicKey = $publicKey.Replace("'", "'\''")
$remoteCommand = "mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && grep -qxF '$escapedPublicKey' ~/.ssh/authorized_keys || printf '%s\n' '$escapedPublicKey' >> ~/.ssh/authorized_keys; chmod 700 ~/.ssh; chmod 600 ~/.ssh/authorized_keys; chown -R ${User}:${User} ~/.ssh 2>/dev/null || true; tail -n 3 ~/.ssh/authorized_keys"

Write-Host "Autorizando llave SSH en $remote puerto $Port"
Write-Host "Cuando pida password, escribe la contraseña de Ubuntu del usuario $User."

ssh -o StrictHostKeyChecking=accept-new -p $Port $remote $remoteCommand

Write-Host ""
Write-Host "Probando conexion sin password..."
ssh -i $keyPath -p $Port -o BatchMode=yes $remote "echo SSH_OK && hostname -I && ip -br addr"
