# Script to remove all problematic profile files
# This will clean up all profile files that are causing compilation errors

$profileDir = "d:\red social - copia\app\src\main\java\conectate\com\presentation\profile"

if (Test-Path $profileDir) {
    Write-Host "Removing entire profile directory: $profileDir" -ForegroundColor Yellow
    Remove-Item $profileDir -Recurse -Force
}

Write-Host "Profile errors cleanup complete!" -ForegroundColor Cyan
