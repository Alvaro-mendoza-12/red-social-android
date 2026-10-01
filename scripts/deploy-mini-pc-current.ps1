param(
  [string]$HostName = "192.168.18.22",
  [string]$User = "alvaro",
  [string]$KeyPath = "$env:USERPROFILE\.ssh\conectate_mini_ed25519",
  [string]$TrustedWebOrigin = "https://red-social-android.vercel.app",
  [string]$StablePublicBaseUrl = "",
  [switch]$AllowEphemeralPublicOrigin,
  [switch]$SkipBackup,
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$fallbackSource = Join-Path $repoRoot "infrastructure\local-fallback"
$signalingSource = Join-Path $repoRoot "signaling-server"
$auditScript = Join-Path $repoRoot "scripts\audit-mini-pc-drift.sh"
$healthAuditScript = Join-Path $repoRoot "scripts\audit-mini-pc-health.sh"
$backendDiscoveryTool = Join-Path $repoRoot "tools\backend-discovery-envelope.mjs"

foreach ($path in @($fallbackSource, $signalingSource, $auditScript, $healthAuditScript, $backendDiscoveryTool, $KeyPath)) {
  if (!(Test-Path -LiteralPath $path)) {
    throw "No existe: $path"
  }
}

if ($TrustedWebOrigin -notmatch '^https://[A-Za-z0-9.-]+(?::[0-9]+)?$') {
  throw "TrustedWebOrigin debe ser un origen HTTPS sin ruta: $TrustedWebOrigin"
}
if ($StablePublicBaseUrl -and $StablePublicBaseUrl -notmatch '^https://[A-Za-z0-9.-]+\.ts\.net(?::443|:8443)?$') {
  throw "StablePublicBaseUrl debe ser un origen Tailscale Funnel HTTPS en 443 o 8443"
}

$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$release = "conectate-mini-pc-$stamp"
$commit = (& git -C $repoRoot rev-parse --short=12 HEAD 2>$null).Trim()
if ([string]::IsNullOrWhiteSpace($commit)) { $commit = "unknown" }
$target = "$User@$HostName"
$connectionOptions = @(
  "-o", "StrictHostKeyChecking=accept-new",
  "-o", "ConnectTimeout=20",
  "-o", "ServerAliveInterval=15",
  "-o", "ServerAliveCountMax=20",
  "-o", "TCPKeepAlive=yes"
)
$ssh = @("-i", $KeyPath) + $connectionOptions
$scp = @("-i", $KeyPath) + $connectionOptions
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("conectate-deploy-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

function Invoke-Remote([string]$Command) {
  # PowerShell here-strings use CRLF on Windows. Passing those bytes directly
  # to Bash makes options such as `set -o pipefail` end in a literal `\r`.
  $normalizedCommand = $Command.Replace("`r`n", "`n").Replace("`r", "`n")
  & ssh @ssh $target $normalizedCommand
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

  $fallbackArchiveDigest = (Get-FileHash -LiteralPath $fallbackArchive -Algorithm SHA256).Hash.ToLowerInvariant()
  $signalingArchiveDigest = (Get-FileHash -LiteralPath $signalingArchive -Algorithm SHA256).Hash.ToLowerInvariant()
  $digestInput = [Text.Encoding]::UTF8.GetBytes("$fallbackArchiveDigest`n$signalingArchiveDigest")
  $digestProvider = [Security.Cryptography.SHA256]::Create()
  try {
    $sourceDigest = [BitConverter]::ToString($digestProvider.ComputeHash($digestInput)).Replace('-', '').ToLowerInvariant()
  } finally {
    $digestProvider.Dispose()
  }

  Write-Host "==> Verificando servidor"
  Invoke-Remote "hostname; test -d ~/conectate-local-fallback; test -s ~/conectate-local-fallback/.env"

  # Preflight may update .env. Back up its original state before any mutation.
  if (-not $SkipBackup) {
    Write-Host "==> Backup antes de modificar configuracion o fuentes"
    Invoke-Remote "cd ~/conectate-local-fallback && ./scripts/backup-ubuntu-primary.sh"
  } else {
    Write-Host "==> Reutilizando backup reciente y verificando sus hashes"
    Invoke-Remote @'
set -Eeuo pipefail
latest="$(readlink -f "$HOME/conectate-backups/latest")"
test -n "$latest" -a -d "$latest"
if [ -z "$(find "$latest" -maxdepth 0 -mmin -30 -print -quit)" ]; then
  echo "ERROR: -SkipBackup solo acepta un backup completo de los últimos 30 minutos" >&2
  exit 1
fi
(
  cd "$latest"
  test -s postgres.dump
  test -s storage.tar.gz
  test -s ai-chatbot-data.tar.gz
  test -s metadata.json
  sha256sum -c SHA256SUMS
)
echo "RECENT_BACKUP_REUSED=$latest"
'@
  }

  Write-Host "==> Preflight de secretos y origenes de produccion"
  $preflight = @'
set -Eeuo pipefail
cd ~/conectate-local-fallback
trusted_origin='__TRUSTED_WEB_ORIGIN__'
stable_public_origin='__STABLE_PUBLIC_BASE_URL__'

ensure_secret() {
  local key="$1"
  if ! grep -q "^${key}=" .env || [ -z "$(sed -n "s/^${key}=//p" .env | tail -n 1)" ]; then
    umask 077
    printf '\n%s=%s\n' "$key" "$(openssl rand -hex 64)" >> .env
  fi
}

upsert_env() {
  local key="$1" value="$2"
  if grep -q "^${key}=" .env; then
    sed -i "s#^${key}=.*#${key}=${value}#" .env
  else
    printf '\n%s=%s\n' "$key" "$value" >> .env
  fi
}

ensure_secret STORAGE_SIGNING_SECRET
ensure_secret REALTIME_INTERNAL_SECRET

if [ -n "$stable_public_origin" ]; then
  curl -4 -fsS --max-time 10 "$stable_public_origin/ready" >/dev/null
  upsert_env STABLE_PUBLIC_BASE_URL "$stable_public_origin"
  upsert_env PUBLIC_BASE_URL "$stable_public_origin"
fi

cors_origin="$(sed -n 's/^CORS_ORIGIN=//p' .env | tail -n 1)"
if [ -z "$cors_origin" ] || [ "$cors_origin" = '*' ]; then
  upsert_env CORS_ORIGIN "$trusted_origin"
fi

signaling_origins="$(sed -n 's/^SIGNALING_ALLOWED_ORIGINS=//p' .env | tail -n 1)"
if [ -z "$signaling_origins" ] || [ "$signaling_origins" = '*' ]; then
  upsert_env SIGNALING_ALLOWED_ORIGINS "$trusted_origin"
fi

reset_delivery="$(sed -n 's/^PASSWORD_RESET_DELIVERY=//p' .env | tail -n 1 | tr '[:upper:]' '[:lower:]')"
if [ -z "$reset_delivery" ] || [ "$reset_delivery" = 'console' ] || [ "$reset_delivery" = 'response' ]; then
  upsert_env PASSWORD_RESET_DELIVERY disabled
fi

for key in POSTGRES_PASSWORD JWT_SECRET TURN_SECRET STORAGE_SIGNING_SECRET REALTIME_INTERNAL_SECRET; do
  value="$(sed -n "s/^${key}=//p" .env | tail -n 1)"
  if [ -z "$value" ]; then
    echo "ERROR: falta $key en .env" >&2
    exit 1
  fi
done

docker compose config --quiet
echo PRODUCTION_PREFLIGHT_OK
'@
  Invoke-Remote $preflight.Replace('__TRUSTED_WEB_ORIGIN__', $TrustedWebOrigin).Replace('__STABLE_PUBLIC_BASE_URL__', $StablePublicBaseUrl)

  Write-Host "==> Subiendo fuentes actuales"
  Copy-Remote $fallbackArchive "/tmp/local-fallback-source.tar.gz"
  Copy-Remote $signalingArchive "/tmp/signaling-source.tar.gz"
  Copy-Remote $auditScript "~/audit-mini-pc-drift.sh"
  Copy-Remote $healthAuditScript "~/audit-mini-pc-health.sh"
  Copy-Remote $backendDiscoveryTool "/tmp/backend-discovery-envelope.mjs"

  Write-Host "==> Instalando fuentes sin borrar .env/secrets/datos"
  # This block must be a literal here-string. An expandable PowerShell
  # here-string rewrites Bash variables such as $HOME and $marker on the
  # Windows client before SSH receives them.
  Invoke-Remote @'
set -Eeuo pipefail
mkdir -p ~/conectate-local-fallback ~/signaling-server
marker="$HOME/conectate-local-fallback/.conectate-release"
if [ -L "$marker" ]; then
  echo "ERROR: .conectate-release no puede ser un enlace simbolico" >&2
  exit 1
fi
if [ -d "$marker" ]; then
  if find "$marker" -mindepth 1 -print -quit | grep -q .; then
    echo "ERROR: .conectate-release es un directorio no vacio" >&2
    exit 1
  fi
  rmdir -- "$marker"
fi
if [ ! -e "$marker" ]; then
  umask 077
  : > "$marker"
fi
tar -xzf /tmp/local-fallback-source.tar.gz -C ~/conectate-local-fallback
tar -xzf /tmp/signaling-source.tar.gz -C ~/signaling-server
install -m 0755 /tmp/backend-discovery-envelope.mjs ~/conectate-local-fallback/scripts/backend-discovery-envelope.mjs
rm -f /tmp/local-fallback-source.tar.gz /tmp/signaling-source.tar.gz /tmp/backend-discovery-envelope.mjs
  chmod +x ~/audit-mini-pc-drift.sh ~/audit-mini-pc-health.sh ~/conectate-local-fallback/scripts/*.sh
  mkdir -p ~/bin
  if [ -f ~/bin/conectate-cloudflare-tunnel.sh ]; then
    cp -p ~/bin/conectate-cloudflare-tunnel.sh ~/bin/conectate-cloudflare-tunnel.sh.previous
  fi
  install -m 0755 ~/conectate-local-fallback/scripts/conectate-cloudflare-tunnel.sh ~/bin/conectate-cloudflare-tunnel.sh
'@

  $ephemeralOriginOverride = if ($AllowEphemeralPublicOrigin) {
    "ALLOW_EPHEMERAL_PUBLIC_ORIGIN_OVERRIDE=true "
  } else {
    ""
  }

  if (-not $SkipBuild) {
    Write-Host "==> Reconstruyendo imagenes y actualizando servicios por etapas"
    Invoke-Remote "cd ~/conectate-local-fallback && ${ephemeralOriginOverride}./scripts/deploy-stack-safely.sh"
  } else {
    Write-Host "==> Saltando build; actualizando servicios por etapas"
    Invoke-Remote "cd ~/conectate-local-fallback && ${ephemeralOriginOverride}./scripts/deploy-stack-safely.sh --skip-build"
  }

  Write-Host "==> Validando ingreso publico y servicios"
  Invoke-Remote @'
set -Eeuo pipefail
cd ~/conectate-local-fallback
public_origin="$(sed -n 's/^PUBLIC_BASE_URL=//p' .env | tail -1 | tr -d '\r"')"
if [[ "$public_origin" =~ ^https://[A-Za-z0-9.-]+\.ts\.net(:443|:8443)?$ ]]; then
  # The current ingress uses Funnel. Restarting Cloudflare can overwrite it.
  curl -4 -fsS --max-time 10 "$public_origin/ready" >/dev/null
else
  systemctl --user restart conectate-cloudflare-tunnel.service
  sleep 20
fi
~/bin/conectate-status
~/audit-mini-pc-drift.sh | sed -n '1,180p'
'@

  Write-Host "==> Confirmando el release solo despues de health checks"
  $markerFinalize = @'
set -Eeuo pipefail
cd ~/conectate-local-fallback
marker="$HOME/conectate-local-fallback/.conectate-release"
previous="$marker.previous"
expected_commit="__EXPECTED_COMMIT__"
expected_release="__EXPECTED_RELEASE__"
expected_source_digest="__EXPECTED_SOURCE_DIGEST__"

restore_marker() {
  local status=$?
  trap - EXIT
  if [ "$status" -ne 0 ]; then
    if [ -f "$previous" ]; then
      mv -f -- "$previous" "$marker"
    else
      rm -f -- "$marker"
    fi
    docker compose up -d --no-deps --force-recreate api >/dev/null 2>&1 || true
  else
    rm -f -- "$previous"
  fi
  exit "$status"
}
trap restore_marker EXIT

if [ -f "$marker" ]; then
  cp -p -- "$marker" "$previous"
else
  rm -f -- "$previous"
fi

umask 077
cat > "$marker.next" <<EOF
release=$expected_release
commit=$expected_commit
source_digest=$expected_source_digest
deployed_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
source=deploy-mini-pc-current.ps1
host=$(hostname)
EOF
mv -f -- "$marker.next" "$marker"

docker compose up -d --no-deps --force-recreate api
for attempt in $(seq 1 60); do
  payload="$(curl -fsS --max-time 5 http://127.0.0.1:3001/ready 2>/dev/null || true)"
  # SSH on Windows can alter nested quote escapes in the remote command.
  # These expected values are unique release identifiers in /ready.
  if printf '%s' "$payload" | grep -Fq "$expected_release" &&
     printf '%s' "$payload" | grep -Fq "$expected_commit" &&
     printf '%s' "$payload" | grep -Fq "$expected_source_digest"; then
    trap - EXIT
    rm -f -- "$previous"
    echo "RELEASE_MARKER_COMMITTED=$expected_release"
    exit 0
  fi
  sleep 2
done

echo "ERROR: API no anuncio el commit esperado despues de actualizar release metadata" >&2
exit 1
'@
  Invoke-Remote $markerFinalize.Replace('__EXPECTED_COMMIT__', $commit).Replace('__EXPECTED_RELEASE__', $release).Replace('__EXPECTED_SOURCE_DIGEST__', $sourceDigest)

  Write-Host ""
  Write-Host "DEPLOY_MINI_PC_OK=$release" -ForegroundColor Green
} finally {
  $resolvedTemporary = [IO.Path]::GetFullPath($tmp)
  $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
  if ([IO.Path]::GetDirectoryName($resolvedTemporary).TrimEnd('\', '/') -eq $expectedParent -and
      [IO.Path]::GetFileName($resolvedTemporary) -match '^conectate-deploy-[a-f0-9]{32}$') {
    Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force -ErrorAction SilentlyContinue
  } else {
    throw "Se rechazo limpiar una ruta temporal fuera del destino de despliegue."
  }
}
