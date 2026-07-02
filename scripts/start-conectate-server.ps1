param(
    [string]$User = "alvaro",
    [string]$HostName = "192.168.18.22",
    [int]$Port = 22,
    [string]$SshKey = "$env:USERPROFILE\.ssh\conectate_mini_ed25519"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$keyPath = $SshKey
if (-not (Test-Path $keyPath)) {
    throw "No existe la llave SSH $keyPath."
}

$remote = "$User@$HostName"
$remoteScript = @'
set -Eeuo pipefail
cd "$HOME/conectate-local-fallback"

echo "==> Iniciando Docker Compose"
if ! docker compose up -d --remove-orphans; then
  echo "==> Reparando referencias de contenedores anteriores"
  docker compose down --remove-orphans
  docker compose up -d --remove-orphans
fi

echo "==> Esperando API Ubuntu"
for i in $(seq 1 90); do
  if curl -fsS --max-time 5 http://127.0.0.1:3080/ready >/dev/null 2>&1; then
    break
  fi
  if [ "$i" -eq 90 ]; then
    docker compose ps
    docker compose logs --tail=100 api edge signaling realtime-gateway
    exit 1
  fi
  sleep 2
done

expected="api db edge realtime-gateway signaling"
for service in $expected; do
  container_id="$(docker compose ps -q "$service")"
  if [ -z "$container_id" ]; then
    echo "ERROR: falta el servicio $service"
    docker compose ps
    exit 1
  fi
  state="$(docker inspect -f '{{.State.Status}}' "$container_id")"
  if [ "$state" != "running" ]; then
    echo "ERROR: $service esta en estado $state"
    docker compose ps
    exit 1
  fi
done

echo "DOCKER_READY"
docker compose ps
'@

$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteScript))
$remoteCommand = "printf %s $encoded | base64 -d | bash"

Write-Output "==> Verificando SSH y servicios Ubuntu"
& ssh -i $keyPath -p $Port -o BatchMode=yes -o ConnectTimeout=10 $remote $remoteCommand
if ($LASTEXITCODE -ne 0) {
    throw "Ubuntu no pudo dejar Docker listo. Revisa que la VM este encendida y el reenvio SSH 2222 activo."
}

Write-Output "==> Iniciando Cloudflare y publicando descubrimiento DuckDNS"
& (Join-Path $PSScriptRoot "start-cloudflare-quick-tunnel.ps1") `
    -User $User `
    -HostName $HostName `
    -Port $Port `
    -SshKey $SshKey `
    -PublishDiscovery
if ($LASTEXITCODE -ne 0) {
    throw "Docker funciona, pero no se pudo publicar el tunel."
}

Write-Output "SERVER_READY"
Write-Output "La app Android ya puede usar Ubuntu mediante DuckDNS."
