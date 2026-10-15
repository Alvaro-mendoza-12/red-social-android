param(
    [Parameter(Mandatory = $true)]
    [string]$PublicUrl,
    [switch]$PublishDiscovery,
    [string]$DuckDnsDomain = 'conectate-alvaro',
    [string]$DiscoveryPrivateKeyPath = $env:NOVVIN_BACKEND_DISCOVERY_PRIVATE_KEY_FILE,
    [ValidateRange(60, 604800)]
    [int]$DiscoveryTtlSeconds = 86400
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$normalized = $PublicUrl.Trim().TrimEnd('/')
if ($normalized -notmatch '^https://[a-zA-Z0-9.-]+(?::\d+)?$') {
    throw "La URL publica debe ser HTTPS y no debe incluir rutas: $PublicUrl"
}

$discoveryEnvelope = ''
$discoveryPublicKey = ''
if ($DiscoveryPrivateKeyPath) {
    $resolvedPrivateKeyPath = [IO.Path]::GetFullPath($DiscoveryPrivateKeyPath)
    if (-not (Test-Path -LiteralPath $resolvedPrivateKeyPath -PathType Leaf)) {
        throw "No existe la clave privada de descubrimiento: $resolvedPrivateKeyPath"
    }
    $discoveryTool = Join-Path $root 'tools\backend-discovery-envelope.mjs'
    $discoveryPublicKey = (& node $discoveryTool public --private-key $resolvedPrivateKeyPath).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $discoveryPublicKey) {
        throw 'No se pudo derivar la clave publica de descubrimiento.'
    }
    $discoveryEnvelope = (& node $discoveryTool sign --private-key $resolvedPrivateKeyPath --url $normalized --ttl-seconds $DiscoveryTtlSeconds).Trim()
    if ($LASTEXITCODE -ne 0 -or $discoveryEnvelope -notmatch '^novvin1:') {
        throw 'No se pudo firmar el endpoint de descubrimiento.'
    }
} elseif ($PublishDiscovery) {
    throw 'PublishDiscovery exige -DiscoveryPrivateKeyPath o NOVVIN_BACKEND_DISCOVERY_PRIVATE_KEY_FILE.'
}

$configPath = Join-Path $root 'NoWing-web\public\backend-config.json'
$configNeedsUpdate = $true
if (Test-Path $configPath) {
    try {
        $currentConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        $configNeedsUpdate = (
            [int]$currentConfig.schemaVersion -ne 2 -or
            [string]$currentConfig.environment -ne 'beta' -or
            ([string]$currentConfig.backendUrl).Trim().TrimEnd('/') -ne $normalized -or
            ([string]$currentConfig.discoveryEnvelope).Trim() -ne $discoveryEnvelope
        )
    } catch {
        $configNeedsUpdate = $true
    }
}

if ($configNeedsUpdate) {
    $payload = [ordered]@{
        schemaVersion = 2
        environment = 'beta'
        backendUrl = $normalized
        discoveryEnvelope = $discoveryEnvelope
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
    BACKEND_DISCOVERY_HOST = "$DuckDnsDomain.duckdns.org"
}
$webProperties = [ordered]@{
    VITE_FALLBACK_API_URL = $normalized
    VITE_SIGNALING_URL = $normalized
    VITE_BACKEND_DISCOVERY_HOST = "$DuckDnsDomain.duckdns.org"
}
if ($discoveryPublicKey) {
    $androidProperties.BACKEND_DISCOVERY_PUBLIC_KEY = $discoveryPublicKey
    $webProperties.VITE_BACKEND_DISCOVERY_PUBLIC_KEY = $discoveryPublicKey
}
Set-PropertyLines (Join-Path $root 'secrets.properties') $androidProperties
Set-PropertyLines (Join-Path $root 'NoWing-web\.env') $webProperties

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
            [uri]::EscapeDataString($discoveryEnvelope)
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
