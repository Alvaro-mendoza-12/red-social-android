param(
    [Parameter(Mandatory = $true)]
    [string]$PublicUrl,
    [switch]$PublishDiscovery,
    [string]$DuckDnsDomain = 'conectate-alvaro'
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$normalized = $PublicUrl.Trim().TrimEnd('/')
if ($normalized -notmatch '^https://[a-zA-Z0-9.-]+(?::\d+)?$') {
    throw "La URL publica debe ser HTTPS y no debe incluir rutas: $PublicUrl"
}

$configPath = Join-Path $root 'Conectate-web\public\backend-config.json'
$configNeedsUpdate = $true
if (Test-Path $configPath) {
    try {
        $currentConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        $configNeedsUpdate = (
            [int]$currentConfig.schemaVersion -ne 1 -or
            [string]$currentConfig.environment -ne 'beta' -or
            ([string]$currentConfig.backendUrl).Trim().TrimEnd('/') -ne $normalized
        )
    } catch {
        $configNeedsUpdate = $true
    }
}

if ($configNeedsUpdate) {
    $payload = [ordered]@{
        schemaVersion = 1
        environment = 'beta'
        backendUrl = $normalized
        updatedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    }
    $payload | ConvertTo-Json | Set-Content -Path $configPath -Encoding utf8
}

function Set-FileContentWithRetry([string]$Path, [string[]]$Lines) {
    $directory = Split-Path -Parent $Path
    $temporaryPath = Join-Path $directory ('.' + [IO.Path]::GetFileName($Path) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)

    try {
        [IO.File]::WriteAllLines($temporaryPath, $Lines, $utf8WithoutBom)
        for ($attempt = 1; $attempt -le 12; $attempt++) {
            try {
                Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
                return
            } catch {
                if ($attempt -eq 12) { throw }
                Start-Sleep -Milliseconds (100 * $attempt)
            }
        }
    } finally {
        if (Test-Path $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Set-PropertyLines([string]$Path, [Collections.IDictionary]$Properties) {
    if (-not (Test-Path $Path)) { return }
    $lines = @(Get-Content -Path $Path)
    $updated = @{}
    $result = foreach ($line in $lines) {
        $matchedName = $null
        foreach ($name in $Properties.Keys) {
            if ($line -match ('^' + [regex]::Escape([string]$name) + '=')) {
                $matchedName = [string]$name
                break
            }
        }
        if ($null -eq $matchedName) {
            $line
        } elseif (-not $updated.ContainsKey($matchedName)) {
            $updated[$matchedName] = $true
            "$matchedName=$($Properties[$matchedName])"
        }
    }
    foreach ($name in $Properties.Keys) {
        if (-not $updated.ContainsKey([string]$name)) {
            $result += "$name=$($Properties[$name])"
        }
    }
    Set-FileContentWithRetry -Path $Path -Lines $result
}

$androidProperties = [ordered]@{
    FALLBACK_API_URL = $normalized
    LOCAL_FALLBACK_API_URL = $normalized
    SIGNALING_URL = $normalized
}
$webProperties = [ordered]@{
    VITE_FALLBACK_API_URL = $normalized
    VITE_SIGNALING_URL = $normalized
}
Set-PropertyLines (Join-Path $root 'secrets.properties') $androidProperties
Set-PropertyLines (Join-Path $root 'Conectate-web\.env') $webProperties

Write-Output "ENDPOINT_UPDATED=$normalized"
Write-Output "DISCOVERY_FILE=$configPath"
Write-Output "DISCOVERY_CHANGED=$configNeedsUpdate"

if ($PublishDiscovery) {
    $credentialPath = Join-Path $env:LOCALAPPDATA 'Conectate\duckdns-token.dpapi'
    if (-not (Test-Path $credentialPath)) {
        throw "Falta el token cifrado. Ejecuta scripts\configure-duckdns-discovery.ps1 una sola vez."
    }
    # Set-Content appends a newline; ConvertTo-SecureString requires the DPAPI payload only.
    $encryptedToken = (Get-Content -Path $credentialPath -Raw).Trim()
    if (-not $encryptedToken) {
        throw "El archivo de credenciales DuckDNS esta vacio. Ejecuta scripts\configure-duckdns-discovery.ps1 nuevamente."
    }
    $secureToken = $encryptedToken | ConvertTo-SecureString
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    try {
        $plainToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
        $query = 'https://www.duckdns.org/update?domains={0}&token={1}&txt={2}&verbose=true' -f @(
            [uri]::EscapeDataString($DuckDnsDomain)
            [uri]::EscapeDataString($plainToken)
            [uri]::EscapeDataString($normalized)
        )
        $result = Invoke-RestMethod -Uri $query -Method Get
        if ([string]$result -notmatch '^OK') {
            throw "DuckDNS rechazo la actualizacion TXT."
        }
        Write-Output "DUCKDNS_DISCOVERY_UPDATED=$DuckDnsDomain.duckdns.org"
    } finally {
        if ($tokenPointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
        }
    }
}
