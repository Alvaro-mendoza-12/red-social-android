param(
    [string]$HostName = '192.168.18.22',
    [string]$User = 'alvaro',
    [string]$KeyPath = "$env:USERPROFILE\.ssh\conectate_mini_ed25519",
    [int]$Port = 22,
    [string]$RemotePrivateKeyPath = '/home/alvaro/.config/novvin/backend-discovery-private.pem'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not (Test-Path -LiteralPath $KeyPath -PathType Leaf)) {
    throw "No existe la clave SSH: $KeyPath"
}
if ($RemotePrivateKeyPath -notmatch '^/home/[a-z_][a-z0-9_-]*/\.config/novvin/[a-zA-Z0-9._-]+\.pem$') {
    throw 'RemotePrivateKeyPath debe permanecer dentro de /home/<usuario>/.config/novvin y terminar en .pem.'
}

$target = "$User@$HostName"
$sshOptions = @(
    '-i', $KeyPath,
    '-p', "$Port",
    '-o', 'StrictHostKeyChecking=accept-new',
    '-o', 'ConnectTimeout=20'
)

$remoteScript = @'
set -Eeuo pipefail
private_key='__PRIVATE_KEY__'
config_dir="$(dirname "$private_key")"
install -d -m 0700 "$config_dir"
if [ ! -s "$private_key" ]; then
  umask 077
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$private_key"
fi
chmod 0600 "$private_key"
openssl pkey -in "$private_key" -pubout -outform DER | base64 -w0
printf '\n'
'@
$quotedRemotePath = "'$RemotePrivateKeyPath'"
$publicKey = (& ssh @sshOptions $target $remoteScript.Replace("'__PRIVATE_KEY__'", $quotedRemotePath)).Trim()
if ($LASTEXITCODE -ne 0 -or $publicKey -notmatch '^[A-Za-z0-9+/]{80,}={0,2}$') {
    throw 'No se pudo generar o leer la clave publica de descubrimiento en Ubuntu.'
}

function Set-Property([string]$Path, [string]$Name, [string]$Value) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $lines = @(Get-Content -LiteralPath $Path)
    $found = $false
    $updated = foreach ($line in $lines) {
        if (-not $found -and $line -match ('^' + [regex]::Escape($Name) + '=')) {
            $found = $true
            "$Name=$Value"
        } else {
            $line
        }
    }
    if (-not $found) { $updated += "$Name=$Value" }
    $temporary = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    try {
        [IO.File]::WriteAllLines($temporary, $updated, $utf8)
        Move-Item -LiteralPath $temporary -Destination $Path -Force
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
}

Set-Property (Join-Path $repoRoot 'secrets.properties') 'BACKEND_DISCOVERY_PUBLIC_KEY' $publicKey
Set-Property (Join-Path $repoRoot 'Conectate-web\.env') 'VITE_BACKEND_DISCOVERY_PUBLIC_KEY' $publicKey

$envUpdate = @'
set -Eeuo pipefail
cd ~/conectate-local-fallback
private_key='__PRIVATE_KEY__'
if grep -q '^BACKEND_DISCOVERY_PRIVATE_KEY_FILE=' .env; then
  sed -i "s#^BACKEND_DISCOVERY_PRIVATE_KEY_FILE=.*#BACKEND_DISCOVERY_PRIVATE_KEY_FILE=$private_key#" .env
else
  printf '\nBACKEND_DISCOVERY_PRIVATE_KEY_FILE=%s\n' "$private_key" >> .env
fi
if ! grep -q '^BACKEND_DISCOVERY_TTL_SECONDS=' .env; then
  printf 'BACKEND_DISCOVERY_TTL_SECONDS=86400\n' >> .env
fi
'@
& ssh @sshOptions $target $envUpdate.Replace("'__PRIVATE_KEY__'", $quotedRemotePath)
if ($LASTEXITCODE -ne 0) {
    throw 'La clave existe, pero no se pudo actualizar .env del backend.'
}

Write-Output 'BACKEND_DISCOVERY_SIGNING_READY=true'
Write-Output "SERVER_PRIVATE_KEY=$RemotePrivateKeyPath"
Write-Output 'PRIVATE_KEY_COPIED_TO_LAPTOP=false'
Write-Output "PUBLIC_KEY_CONFIGURED_ANDROID=$((Test-Path (Join-Path $repoRoot 'secrets.properties')))"
Write-Output "PUBLIC_KEY_CONFIGURED_WEB=$((Test-Path (Join-Path $repoRoot 'Conectate-web\.env')))"
Write-Output 'Siguiente paso: desplegar el backend y recompilar Android/web para incluir la clave publica.'
