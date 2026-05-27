$files = @(
    "app\src\main\java\conectate\com\architecture\enterprise\SecuritySystem.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\PerformanceOptimization.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\MonitoringSystem.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\LoadTesting.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\InfrastructureScaling.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\EnterpriseValidationReport.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\ComprehensiveTechnicalAudit.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\CodeReviewValidation.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\TechnicalAuditSummaryReport.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\TestingFramework.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\CI-CD-Pipeline.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\AutomaticImprovementsImplementation.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\CleanArchitectureImplementation.kt",
    "app\src\main\java\conectate\com\architecture\enterprise\CleanArchitectureLayers.kt",
    "app\src\main\java\conectate\com\backup\BackupRecoverySystem.kt",
    "app\src\main\java\conectate\com\core\exceptions\RobustExceptionHandler.kt"
)

foreach ($f in $files) {
    $fullPath = Join-Path $PSScriptRoot $f
    if (Test-Path $fullPath) {
        $content = Get-Content $fullPath -Raw -Encoding UTF8
        $content = $content -replace '@Singleton\r?\n', "`n"
        $content = $content -replace '@Inject\s+', ' '
        $content = $content -replace '@Inject\r?\n', "`n"
        $content = $content -replace '@ApplicationContext\s+', ' '
        $content = $content -replace 'import javax\.inject\.Inject\r?\n', ''
        $content = $content -replace 'import javax\.inject\.Singleton\r?\n', ''
        $content = $content -replace 'import dagger\.hilt\.android\.qualifiers\.ApplicationContext\r?\n', ''
        Set-Content $fullPath -Value $content -Encoding UTF8 -NoNewline
        Write-Host "Fixed: $f"
    } else {
        Write-Host "NOT FOUND: $fullPath"
    }
}
Write-Host "Done!"
