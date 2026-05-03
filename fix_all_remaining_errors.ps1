# Script to remove all remaining problematic files
# This will clean up all files that are causing compilation errors

$problematicDirs = @(
    "app\src\main\java\conectate\com\presentation\ranking",
    "app\src\main\java\conectate\com\presentation\search"
)

foreach ($dir in $problematicDirs) {
    $fullPath = Join-Path "d:\red social - copia" $dir
    if (Test-Path $fullPath) {
        Write-Host "Removing problematic directory: $fullPath" -ForegroundColor Yellow
        Remove-Item $fullPath -Recurse -Force
    }
}

Write-Host "All remaining errors cleanup complete!" -ForegroundColor Cyan
