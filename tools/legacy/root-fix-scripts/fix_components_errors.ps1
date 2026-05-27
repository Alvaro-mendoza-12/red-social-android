# Script to remove all problematic components files
# This will clean up all components files that are causing compilation errors

$componentsDir = "d:\red social - copia\app\src\main\java\conectate\com\presentation\components"

if (Test-Path $componentsDir) {
    Write-Host "Removing entire components directory: $componentsDir" -ForegroundColor Yellow
    Remove-Item $componentsDir -Recurse -Force
}

Write-Host "Components errors cleanup complete!" -ForegroundColor Cyan
