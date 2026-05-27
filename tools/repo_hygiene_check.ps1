param(
    [switch]$Strict
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root

function Invoke-GitList {
    param(
        [string[]]$Arguments
    )

    git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed"
    }
}

$cachedExisting = @(
    Invoke-GitList @("ls-files") |
        Where-Object { Test-Path -LiteralPath $_ }
)
$newVisible = @(
    Invoke-GitList @("ls-files", "--others", "--exclude-standard") |
        Where-Object { Test-Path -LiteralPath $_ }
)
$tracked = @($cachedExisting + $newVisible | Sort-Object -Unique)

$forbiddenPrefixes = @(
    "backup/",
    "tmp/",
    "app_backup_before_restore/",
    "scratch/",
    "ai/",
    "bio/",
    "build-system/",
    "devops/",
    "dna/",
    "enterprise/",
    "network/",
    "neuromorphic/",
    "performance/",
    "photonic/",
    "quantum/",
    "security/",
    "space/",
    "testing/"
)

$allowedRootFiles = @(
    ".gitignore",
    "AGENTS.md",
    "README.md",
    "build.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "keystore.properties.example",
    "package.json",
    "secrets.properties.example",
    "settings.gradle.kts",
    "vercel.json"
)

$violations = New-Object System.Collections.Generic.List[string]

foreach ($prefix in $forbiddenPrefixes) {
    $matches = @($tracked | Where-Object { $_.StartsWith($prefix, [System.StringComparison]::Ordinal) })
    if ($matches.Count -gt 0) {
        $violations.Add("Tracked files remain under forbidden root prefix '$prefix': $($matches.Count)")
    }
}

$rootFiles = @($tracked | Where-Object { $_ -notmatch "/" })
foreach ($file in $rootFiles) {
    if ($allowedRootFiles -notcontains $file) {
        $violations.Add("Unexpected tracked root file: $file")
    }
}

$counts = [ordered]@{
    AndroidKotlin = @($tracked | Where-Object { $_ -match "^app/src/main/java/.+\.kt$" }).Count
    AndroidUnitTests = @($tracked | Where-Object { $_ -match "^app/src/test/java/.+\.kt$" }).Count
    WebSource = @($tracked | Where-Object { $_ -match "^Conectate-web/src/.+\.(ts|tsx|css)$" }).Count
    SqlFiles = @($tracked | Where-Object { $_ -match "^sql/.+\.sql$" }).Count
    ArchivedFiles = @($tracked | Where-Object { $_ -match "^docs/archive/" }).Count
}

Write-Host "Repo hygiene summary"
foreach ($entry in $counts.GetEnumerator()) {
    Write-Host ("- {0}: {1}" -f $entry.Key, $entry.Value)
}

if ($violations.Count -gt 0) {
    Write-Host ""
    Write-Host "Violations:"
    foreach ($violation in $violations) {
        Write-Host "- $violation"
    }

    if ($Strict) {
        exit 1
    }
} else {
    Write-Host ""
    Write-Host "No tracked hygiene violations found."
}
