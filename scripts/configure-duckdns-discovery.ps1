param(
    [string]$Domain = 'conectate-alvaro'
)

$ErrorActionPreference = 'Stop'
$credentialDir = Join-Path $env:LOCALAPPDATA 'Conectate'
$credentialPath = Join-Path $credentialDir 'duckdns-token.dpapi'
New-Item -ItemType Directory -Path $credentialDir -Force | Out-Null

$token = Read-Host 'Pega el token NUEVO de DuckDNS' -AsSecureString
$token | ConvertFrom-SecureString | Set-Content -Path $credentialPath -Encoding ascii

Write-Output "DUCKDNS_TOKEN_SAVED=$credentialPath"
Write-Output "DUCKDNS_DOMAIN=$Domain.duckdns.org"
Write-Output 'El token quedo cifrado para tu usuario de Windows y fuera del repositorio.'
