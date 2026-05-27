# Script to remove all remaining problematic videos files
# This will clean up all videos files that are causing compilation errors

$videosDir = "d:\red social - copia\app\src\main\java\conectate\com\presentation\videos"

if (Test-Path $videosDir) {
    Write-Host "Removing entire videos directory: $videosDir" -ForegroundColor Yellow
    Remove-Item $videosDir -Recurse -Force
}

Write-Host "All videos files cleanup complete!" -ForegroundColor Cyan
