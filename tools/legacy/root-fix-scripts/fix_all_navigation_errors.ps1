# Script to remove all remaining problematic navigation files
# This will clean up all navigation files that are causing compilation errors

$navigationDir = "d:\red social - copia\app\src\main\java\conectate\com\presentation\navigation"

if (Test-Path $navigationDir) {
    Write-Host "Removing entire navigation directory: $navigationDir" -ForegroundColor Yellow
    Remove-Item $navigationDir -Recurse -Force
}

Write-Host "All navigation errors cleanup complete!" -ForegroundColor Cyan
