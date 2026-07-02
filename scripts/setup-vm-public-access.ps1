param(
    [string]$User = "alvaro",
    [string]$HostName = "127.0.0.1",
    [int]$Port = 2222,
    [string]$PublicDomain = "",
    [string]$DuckDnsToken = ""
)

$ErrorActionPreference = "Stop"

function Quote-BashSingle([string]$Value) {
    return "'" + $Value.Replace("'", "'\''") + "'"
}

if ([string]::IsNullOrWhiteSpace($PublicDomain)) {
    $PublicDomain = Read-Host "Dominio DuckDNS completo, ej. conectate-demo.duckdns.org"
}

if ([string]::IsNullOrWhiteSpace($DuckDnsToken)) {
    $secure = Read-Host "Token DuckDNS" -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $DuckDnsToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
}

$keyPath = Join-Path $env:USERPROFILE ".ssh\codex_conectate_vm"
if (-not (Test-Path $keyPath)) {
    throw "No existe la llave SSH $keyPath. Ejecuta primero scripts\authorize-vm-ssh.ps1"
}

$localScript = "infrastructure\local-fallback\scripts\setup-public-duckdns-caddy.sh"
if (-not (Test-Path $localScript)) {
    throw "No existe $localScript"
}

$remote = "$User@$HostName"

Write-Host "Copiando script a Ubuntu..."
scp -i $keyPath -P $Port $localScript "${remote}:/home/$User/conectate-local-fallback/scripts/setup-public-duckdns-caddy.sh"

$domainQ = Quote-BashSingle $PublicDomain
$tokenQ = Quote-BashSingle $DuckDnsToken
$remoteCommand = "cd ~/conectate-local-fallback && chmod +x scripts/setup-public-duckdns-caddy.sh && PUBLIC_DOMAIN=$domainQ DUCKDNS_TOKEN=$tokenQ bash scripts/setup-public-duckdns-caddy.sh"

Write-Host ""
Write-Host "Ejecutando setup publico en Ubuntu."
Write-Host "Si pide password, escribe la contraseña sudo de Ubuntu del usuario $User."
ssh -tt -i $keyPath -p $Port $remote $remoteCommand

Write-Host ""
Write-Host "Verificacion local por SSH:"
ssh -i $keyPath -p $Port $remote "cd ~/conectate-local-fallback && grep -E '^PUBLIC_BASE_URL=' .env && curl -fsS http://127.0.0.1:3080/ready && echo && systemctl is-active caddy || true"
