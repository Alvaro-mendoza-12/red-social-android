# Script to remove all remaining problematic social files
# This will clean up all social files that are causing compilation errors

$socialDir = "d:\red social - copia\app\src\main\java\conectate\com\presentation\social"

if (Test-Path $socialDir) {
    Write-Host "Removing entire social directory: $socialDir" -ForegroundColor Yellow
    Remove-Item $socialDir -Recurse -Force
}

Write-Host "All social files cleanup complete!" -ForegroundColor Cyan
