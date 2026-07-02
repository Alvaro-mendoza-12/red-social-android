param(
    [string]$User = "alvaro",
    [string]$HostName = "100.91.84.2",
    [int]$Port = 22,
    [string]$LocalUrl = "http://127.0.0.1:3080",
    [string]$SshKey = "$env:USERPROFILE\.ssh\conectate_mini_ed25519",
    [switch]$PublishDiscovery
)

$ErrorActionPreference = "Stop"

$keyPath = $SshKey
if (-not (Test-Path $keyPath)) {
    throw "No existe la llave SSH $keyPath."
}

$remote = "$User@$HostName"

$remoteScript = @'
set -euo pipefail

mkdir -p "$HOME/bin"

if [ ! -x "$HOME/bin/cloudflared" ]; then
  echo "==> Descargando cloudflared"
  curl -L --fail --retry 3 \
    -o "$HOME/bin/cloudflared" \
    "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"
  chmod +x "$HOME/bin/cloudflared"
fi

echo "==> Verificando backend local"
backend_ready=false
for i in $(seq 1 90); do
  if curl -fsS --max-time 5 http://127.0.0.1:3080/ready >/dev/null 2>&1; then
    backend_ready=true
    break
  fi
  sleep 2
done
if [ "$backend_ready" != true ]; then
  echo "ERROR: el backend Ubuntu no respondio antes de iniciar Cloudflare"
  cd "$HOME/conectate-local-fallback"
  docker compose ps || true
  docker compose logs --tail=100 api edge || true
  exit 1
fi

# Reuse a healthy Quick Tunnel instead of changing the public URL on every
# watchdog/start invocation. This avoids needless API restarts and downtime.
existing_url="$(cat "$HOME/conectate-public-url.txt" 2>/dev/null || true)"
if pgrep -x cloudflared >/dev/null 2>&1 \
  && [[ "$existing_url" =~ ^https://[-a-zA-Z0-9.]+\.trycloudflare\.com$ ]] \
  && curl -fsS --max-time 15 "$existing_url/ready" >/dev/null 2>&1; then
  echo "==> Reutilizando tunel Cloudflare saludable"
  echo "PUBLIC_URL=$existing_url"
  exit 0
fi

echo "==> Deteniendo tuneles anteriores"
pkill -TERM -x cloudflared >/dev/null 2>&1 || true
for i in $(seq 1 10); do
  pgrep -x cloudflared >/dev/null 2>&1 || break
  sleep 1
done
pkill -KILL -x cloudflared >/dev/null 2>&1 || true
rm -f "$HOME/cloudflared-conectate.log"

echo "==> Iniciando Cloudflare Quick Tunnel"
nohup "$HOME/bin/cloudflared" tunnel --no-autoupdate --url __LOCAL_URL__ \
  > "$HOME/cloudflared-conectate.log" 2>&1 &

for i in $(seq 1 45); do
  url="$(grep -Eo 'https://[-a-zA-Z0-9.]+\.trycloudflare\.com' "$HOME/cloudflared-conectate.log" | tail -1 || true)"
  if [ -n "$url" ]; then
    echo "$url" > "$HOME/conectate-public-url.txt"
    echo "PUBLIC_URL=$url"
    cd "$HOME/conectate-local-fallback"

    # Updating a temporary public URL must not rebuild the whole stack. The
    # previous full repair stopped API/edge and made tunnel startup race them.
    if grep -q '^PUBLIC_BASE_URL=' .env; then
      sed -i "s|^PUBLIC_BASE_URL=.*|PUBLIC_BASE_URL=$url|" .env
    else
      printf 'PUBLIC_BASE_URL=%s\n' "$url" >> .env
    fi
    docker compose up -d --no-deps --force-recreate api >/tmp/conectate-api-public-url.log 2>&1 || {
      cat /tmp/conectate-api-public-url.log
      exit 1
    }
    for ready_try in $(seq 1 60); do
      curl -fsS --max-time 5 http://127.0.0.1:3080/ready >/dev/null 2>&1 && break
      if [ "$ready_try" -eq 60 ]; then
        docker compose ps
        docker compose logs --tail=100 api edge
        exit 1
      fi
      sleep 2
    done
    echo "READY:"
    curl -fsS "$url/ready"
    echo
    exit 0
  fi
  sleep 2
done

echo "ERROR: Cloudflare no entrego URL a tiempo. Log:"
cat "$HOME/cloudflared-conectate.log"
exit 1
'@

$remoteScript = $remoteScript.Replace("__LOCAL_URL__", $LocalUrl)
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteScript))
$remoteCommand = "printf %s $encoded | base64 -d | bash"

$output = @(ssh -i $keyPath -p $Port $remote $remoteCommand)
$output | ForEach-Object { Write-Output $_ }
if ($LASTEXITCODE -ne 0) {
    throw "No se pudo iniciar el tunel rapido de Cloudflare."
}

$publicUrl = ($output | Select-String -Pattern 'PUBLIC_URL=(https://[^\s]+)' | Select-Object -Last 1).Matches.Groups[1].Value
if (-not $publicUrl) {
    throw "Cloudflare inicio, pero no se pudo leer la URL publica."
}

& (Join-Path $PSScriptRoot 'set-backend-endpoint.ps1') -PublicUrl $publicUrl -PublishDiscovery:$PublishDiscovery
