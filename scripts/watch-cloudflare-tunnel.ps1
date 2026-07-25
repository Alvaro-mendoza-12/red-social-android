param(
    [int]$TimeoutSeconds = 15,
    [string]$User = "alvaro",
    [string]$HostName = "100.91.84.2",
    [int]$Port = 22,
    [string]$SshKey = "$env:USERPROFILE\.ssh\conectate_mini_ed25519"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $root 'Conectate-web\public\backend-config.json'
$mutex = New-Object System.Threading.Mutex($false, 'ConectateCloudflareTunnelWatchdog')

if (-not $mutex.WaitOne(0)) {
    Write-Output 'WATCHDOG_SKIPPED=already_running'
    exit 0
}

try {
    $healthy = $false
    $publicUrl = $null
    if (Test-Path $configPath) {
        $config = Get-Content $configPath -Raw | ConvertFrom-Json
        $publicUrl = [string]$config.backendUrl
        if ($publicUrl) {
            try {
                $ready = Invoke-RestMethod -Uri ($publicUrl.TrimEnd('/') + '/ready') -TimeoutSec $TimeoutSeconds
                $healthy = $ready.ok -eq $true -and $ready.pushNotifications.configured -eq $true
            } catch {
                $healthy = $false
            }
        }
    }

    if ($healthy) {
        Write-Output "WATCHDOG_HEALTHY_LOCAL=$publicUrl"
    } else {
        Write-Output 'WATCHDOG_RECOVERING=cloudflare_quick_tunnel'
    }

    Write-Output 'WATCHDOG_RECONCILING=cloudflare_quick_tunnel'
    & (Join-Path $PSScriptRoot 'start-cloudflare-quick-tunnel.ps1') `
        -User $User `
        -HostName $HostName `
        -Port $Port `
        -SshKey $SshKey `
        -PublishDiscovery
    if ($LASTEXITCODE -ne 0) {
        throw 'No se pudo recuperar el túnel público.'
    }
} finally {
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
