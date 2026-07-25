param(
  [string]$HostName = "192.168.18.22",
  [string]$User = "alvaro",
  [string]$KeyPath = "$env:USERPROFILE\.ssh\conectate_mini_ed25519",
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$fallbackSource = Join-Path $repoRoot "infrastructure\local-fallback"
$signalingSource = Join-Path $repoRoot "signaling-server"
$auditScript = Join-Path $repoRoot "scripts\audit-mini-pc-drift.sh"

foreach ($path in @($fallbackSource, $signalingSource, $auditScript, $KeyPath)) {
  if (!(Test-Path -LiteralPath $path)) {
    throw "No existe: $path"
  }
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$release = "conectate-mini-pc-$stamp"
$target = "$User@$HostName"
$ssh = @("-i", $KeyPath, "-o", "StrictHostKeyChecking=accept-new")
$scp = @("-i", $KeyPath, "-o", "StrictHostKeyChecking=accept-new")
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("conectate-deploy-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

function Invoke-Remote([string]$Command) {
  & ssh @ssh $target $Command
  if ($LASTEXITCODE -ne 0) { throw "SSH fallo: $Command" }
}

function Copy-Remote([string]$Local, [string]$Remote) {
  & scp @scp $Local "${target}:$Remote"
  if ($LASTEXITCODE -ne 0) { throw "SCP fallo: $Local" }
}

try {
  $fallbackArchive = Join-Path $tmp "local-fallback-source.tar.gz"
  $signalingArchive = Join-Path $tmp "signaling-source.tar.gz"

  Write-Host "==> Empaquetando backend local-fallback actual"
  & tar.exe -czf $fallbackArchive `
    --exclude="./.env" `
    --exclude="./secrets" `
    --exclude="./backups" `
    --exclude="./logs" `
    --exclude="./imports/*.csv" `
    --exclude="./imports/*.json" `
    --exclude="./ai-chatbot-data" `
    --exclude="./api/node_modules" `
    --exclude="./realtime-gateway/node_modules" `
    -C $fallbackSource .
  if ($LASTEXITCODE -ne 0) { throw "tar local-fallback fallo" }

  Write-Host "==> Empaquetando signaling-server actual"
  & tar.exe -czf $signalingArchive `
    --exclude="./node_modules" `
    --exclude="./.git" `
    -C $signalingSource .
  if ($LASTEXITCODE -ne 0) { throw "tar signaling fallo" }

  Write-Host "==> Verificando servidor"
  Invoke-Remote "hostname; test -d ~/conectate-local-fallback; test -s ~/conectate-local-fallback/.env"

  Write-Host "==> Backup antes de desplegar"
  Invoke-Remote "cd ~/conectate-local-fallback && ./scripts/backup-ubuntu-primary.sh"

  Write-Host "==> Subiendo fuentes actuales"
  Copy-Remote $fallbackArchive "/tmp/local-fallback-source.tar.gz"
  Copy-Remote $signalingArchive "/tmp/signaling-source.tar.gz"
  Copy-Remote $auditScript "~/audit-mini-pc-drift.sh"

  Write-Host "==> Instalando fuentes sin borrar .env/secrets/datos"
  Invoke-Remote @"
set -Eeuo pipefail
mkdir -p ~/conectate-local-fallback ~/signaling-server
tar -xzf /tmp/local-fallback-source.tar.gz -C ~/conectate-local-fallback
tar -xzf /tmp/signaling-source.tar.gz -C ~/signaling-server
rm -f /tmp/local-fallback-source.tar.gz /tmp/signaling-source.tar.gz
chmod +x ~/audit-mini-pc-drift.sh ~/conectate-local-fallback/scripts/*.sh
cat > ~/conectate-local-fallback/.conectate-release <<'EOF'
release=$release
deployed_at_utc=$((Get-Date).ToUniversalTime().ToString("o"))
source=deploy-mini-pc-current.ps1
host=$HostName
EOF
"@

  if (-not $SkipBuild) {
    Write-Host "==> Reconstruyendo imagenes y actualizando servicios por etapas"
    Invoke-Remote "cd ~/conectate-local-fallback && ./scripts/deploy-stack-safely.sh"
  } else {
    Write-Host "==> Saltando build; actualizando servicios por etapas"
    Invoke-Remote "cd ~/conectate-local-fallback && ./scripts/deploy-stack-safely.sh --skip-build"
  }

  Write-Host "==> Reiniciando tunnel y validando"
  Invoke-Remote "systemctl --user restart conectate-cloudflare-tunnel.service || true; sleep 20; ~/bin/conectate-status; ~/audit-mini-pc-drift.sh | sed -n '1,180p'"

  Write-Host ""
  Write-Host "DEPLOY_MINI_PC_OK=$release" -ForegroundColor Green
} finally {
  Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
