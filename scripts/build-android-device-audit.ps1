param(
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot',
    [string[]]$Tasks = @(':app:assembleAudit')
)

$ErrorActionPreference = 'Stop'
$auditRoot = Split-Path -Parent $PSScriptRoot
$auditConfigDirectory = Join-Path $auditRoot 'app\src\audit'
$auditConfigPath = Join-Path $auditConfigDirectory 'google-services.json'
$sourceConfigPath = Join-Path $auditRoot 'app\google-services.json'
if (Test-Path -LiteralPath $auditConfigPath) {
    throw 'Ya existe configuración Firebase de audit. Compila :app:assembleAudit directamente para conservarla.'
}
if (!(Test-Path -LiteralPath $sourceConfigPath)) { throw 'Falta app/google-services.json.' }

# Reuse local project resources for API/UI QA. This does not register a new
# Firebase Android app or certify push delivery for the production package.
$auditConfig = Get-Content -LiteralPath $sourceConfigPath -Raw | ConvertFrom-Json
$auditClient = @($auditConfig.client | Where-Object {
    $_.client_info.android_client_info.package_name -eq 'conectate.com'
})
if ($auditClient.Count -ne 1) { throw 'Se esperaba un cliente Firebase para conectate.com.' }
$auditClient[0].client_info.android_client_info.package_name = 'conectate.com.qa'
$auditConfig.client = @($auditClient[0])
New-Item -ItemType Directory -Path $auditConfigDirectory -Force | Out-Null
$auditConfig | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $auditConfigPath -Encoding utf8
$priorJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    Push-Location $auditRoot
    try {
        & .\gradlew.bat @Tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Falló la compilación de auditoría: $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $priorJavaHome
    # Remove only the temporary file created above, never the original config.
    Remove-Item -LiteralPath $auditConfigPath
}
