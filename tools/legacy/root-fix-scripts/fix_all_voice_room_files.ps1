# Script to remove all remaining problematic voice room files
# This will clean up all voice room files that are causing compilation errors

$voiceRoomDir = "d:\red social - copia\app\src\main\java\conectate\com\presentation\voicerooms"

if (Test-Path $voiceRoomDir) {
    Write-Host "Removing entire voice rooms directory: $voiceRoomDir" -ForegroundColor Yellow
    Remove-Item $voiceRoomDir -Recurse -Force
}

Write-Host "All voice room files cleanup complete!" -ForegroundColor Cyan
