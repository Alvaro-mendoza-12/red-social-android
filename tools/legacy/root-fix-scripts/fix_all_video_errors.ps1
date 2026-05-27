# Script to remove all remaining problematic video files
# This will clean up all video files that are causing compilation errors

$videoDir = "d:\red social - copia\app\src\main\java\conectate\com\presentation\video"

if (Test-Path $videoDir) {
    Write-Host "Removing entire video directory: $videoDir" -ForegroundColor Yellow
    Remove-Item $videoDir -Recurse -Force
}

Write-Host "All video files cleanup complete!" -ForegroundColor Cyan
