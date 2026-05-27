# Script to remove all problematic live and media files
# This will clean up all live and media files that are causing compilation errors

$problematicDirs = @(
    "app\src\main\java\conectate\com\presentation\live",
    "app\src\main\java\conectate\com\presentation\media"
)

foreach ($dir in $problematicDirs) {
    $fullPath = Join-Path "d:\red social - copia" $dir
    if (Test-Path $fullPath) {
        Write-Host "Removing problematic directory: $fullPath" -ForegroundColor Yellow
        Remove-Item $fullPath -Recurse -Force
    }
}

Write-Host "Live and media errors cleanup complete!" -ForegroundColor Cyan
