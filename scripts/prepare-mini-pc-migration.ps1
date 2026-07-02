[CmdletBinding()]
param(
    [string]$UbuntuHost = "192.168.56.100",
    [string]$UbuntuUser = "alvaro",
    [int]$SshPort = 22,
    [string]$SshKey = "$env:USERPROFILE\.ssh\codex_conectate_vm",
    [string]$RemoteRoot = "/home/alvaro/conectate-local-fallback",
    [string]$RemoteSignalingRoot = "/home/alvaro/signaling-server",
    [string]$OutputDirectory = "$PSScriptRoot\..\migration-output"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$exportScript = Join-Path $repoRoot "infrastructure\local-fallback\scripts\export-mini-pc-migration.sh"
$importScript = Join-Path $repoRoot "infrastructure\local-fallback\scripts\import-mini-pc-migration.sh"
$verifyScript = Join-Path $repoRoot "infrastructure\local-fallback\scripts\verify-mini-pc-migration.sh"

foreach ($path in @($SshKey, $exportScript, $importScript, $verifyScript)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "No existe: $path"
    }
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path $OutputDirectory).Path
$target = "$UbuntuUser@$UbuntuHost"
$sshOptions = @("-p", "$SshPort", "-i", $SshKey, "-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes")
$scpOptions = @("-P", "$SshPort", "-i", $SshKey, "-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes")

Write-Host "Verificando el Ubuntu actual..."
& ssh @sshOptions $target "test -d '$RemoteRoot' && test -s '$RemoteRoot/.env' && test -s '$RemoteRoot/secrets/firebase-service-account.json'"
if ($LASTEXITCODE -ne 0) { throw "El servidor no tiene .env o la cuenta Firebase requerida." }

Write-Host "Sincronizando el código activo que viajará a la mini PC..."
$staging = Join-Path ([IO.Path]::GetTempPath()) ("conectate-migration-source-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $staging -Force | Out-Null
try {
    $fallbackArchive = Join-Path $staging "local-fallback-source.tar.gz"
    $signalingArchive = Join-Path $staging "signaling-source.tar.gz"
    & tar.exe -czf $fallbackArchive `
        --exclude="./.env" `
        --exclude="./secrets" `
        --exclude="./backups" `
        --exclude="./logs" `
        --exclude="./imports/*.csv" `
        --exclude="./imports/*.json" `
        --exclude="./api/storage-data" `
        --exclude="./api/node_modules" `
        --exclude="./realtime-gateway/node_modules" `
        -C (Join-Path $repoRoot "infrastructure\local-fallback") .
    if ($LASTEXITCODE -ne 0) { throw "No se pudo empaquetar local-fallback." }
    & tar.exe -czf $signalingArchive --exclude="./node_modules" --exclude="./.git" `
        -C (Join-Path $repoRoot "signaling-server") .
    if ($LASTEXITCODE -ne 0) { throw "No se pudo empaquetar signaling-server." }

    & scp @scpOptions $fallbackArchive $signalingArchive "${target}:/tmp/"
    if ($LASTEXITCODE -ne 0) { throw "No se pudo copiar el código activo al Ubuntu." }
    $extractCommand = "mkdir -p '$RemoteRoot' '$RemoteSignalingRoot' && tar -xzf /tmp/local-fallback-source.tar.gz -C '$RemoteRoot' && tar -xzf /tmp/signaling-source.tar.gz -C '$RemoteSignalingRoot' && rm -f /tmp/local-fallback-source.tar.gz /tmp/signaling-source.tar.gz"
    & ssh @sshOptions $target $extractCommand
    if ($LASTEXITCODE -ne 0) { throw "No se pudo actualizar el código remoto para la migración." }
}
finally {
    Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Actualizando los dos scripts de migración en Ubuntu..."
& scp @scpOptions $exportScript "${target}:/tmp/export-mini-pc-migration.sh"
if ($LASTEXITCODE -ne 0) { throw "No se pudo copiar el exportador." }
& scp @scpOptions $importScript "${target}:/tmp/import-mini-pc-migration.sh"
if ($LASTEXITCODE -ne 0) { throw "No se pudo copiar el importador." }
& ssh @sshOptions $target "install -m 700 /tmp/export-mini-pc-migration.sh '$RemoteRoot/scripts/export-mini-pc-migration.sh' && install -m 700 /tmp/import-mini-pc-migration.sh '$RemoteRoot/scripts/import-mini-pc-migration.sh' && rm -f /tmp/export-mini-pc-migration.sh /tmp/import-mini-pc-migration.sh"
if ($LASTEXITCODE -ne 0) { throw "No se pudieron instalar los scripts remotos." }

$securePassphrase = Read-Host "Contraseña para cifrar el paquete (guárdala)" -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassphrase)
try {
    $plainPassphrase = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if (
        [string]::IsNullOrWhiteSpace($plainPassphrase) -or
        $plainPassphrase.Length -lt 6 -or
        $plainPassphrase -cnotmatch '^[\x21-\x7E]+$'
    ) {
        throw "Usa al menos 6 caracteres ASCII sin espacios ni tildes (A-Z, a-z, 0-9 y simbolos comunes)."
    }

    Write-Host "Creando respaldo fresco, verificándolo y cifrándolo..."
    $remoteCommand = "IFS= read -r MIGRATION_PASSPHRASE; MIGRATION_PASSPHRASE=`${MIGRATION_PASSPHRASE%`$'\r'}; export MIGRATION_PASSPHRASE; '$RemoteRoot/scripts/export-mini-pc-migration.sh'"
    $result = $plainPassphrase | & ssh @sshOptions $target $remoteCommand
    if ($LASTEXITCODE -ne 0) { throw "El exportador remoto falló." }
    $result | ForEach-Object { Write-Host $_ }

    $bundleLine = $result | Where-Object { $_ -like "MIGRATION_BUNDLE_OK=*" } | Select-Object -Last 1
    if (-not $bundleLine) { throw "El servidor no devolvió la ruta del paquete." }
    $remoteBundle = $bundleLine.Substring("MIGRATION_BUNDLE_OK=".Length).Trim()
    $localBundle = Join-Path $OutputDirectory ([IO.Path]::GetFileName($remoteBundle))

    Write-Host "Descargando paquete cifrado..."
    & scp @scpOptions "${target}:$remoteBundle" $localBundle
    if ($LASTEXITCODE -ne 0) { throw "No se pudo descargar el paquete cifrado." }

    Copy-Item -LiteralPath $importScript -Destination (Join-Path $OutputDirectory "import-mini-pc-migration.sh") -Force
    Copy-Item -LiteralPath $verifyScript -Destination (Join-Path $OutputDirectory "verify-mini-pc-migration.sh") -Force
    $hash = (Get-FileHash -LiteralPath $localBundle -Algorithm SHA256).Hash
    @(
        "Archivo: $([IO.Path]::GetFileName($localBundle))"
        "SHA256: $hash"
        "Creado: $(Get-Date -Format o)"
        "IMPORTANTE: guarda la contraseña fuera de esta carpeta."
    ) | Set-Content -LiteralPath (Join-Path $OutputDirectory "LEEME-MIGRACION.txt") -Encoding UTF8

    Write-Host ""
    Write-Host "MIGRACION_PREPARADA=$localBundle" -ForegroundColor Green
    Write-Host "SHA256=$hash"
    Write-Host "Copia esta carpeta a la mini PC y ejecuta import-mini-pc-migration.sh."
}
finally {
    if ($pointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
    $plainPassphrase = $null
    $securePassphrase = $null
}
