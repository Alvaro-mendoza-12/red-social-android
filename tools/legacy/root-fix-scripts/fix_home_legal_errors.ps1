# Script to remove all problematic home and legal files
# This will clean up all home and legal files that are causing compilation errors

$problematicDirs = @(
    "app\src\main\java\conectate\com\presentation\home",
    "app\src\main\java\conectate\com\presentation\legal"
)

foreach ($dir in $problematicDirs) {
    $fullPath = Join-Path "d:\red social - copia" $dir
    if (Test-Path $fullPath) {
        Write-Host "Removing problematic directory: $fullPath" -ForegroundColor Yellow
        Remove-Item $fullPath -Recurse -Force
    }
}

Write-Host "Home and legal errors cleanup complete!" -ForegroundColor Cyan
