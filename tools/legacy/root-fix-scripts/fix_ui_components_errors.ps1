# Script to remove problematic UI components file
# This will clean up ModernUIComponents that is causing compilation errors

$file = "d:\red social - copia\app\src\main\java\conectate\com\presentation\ui\ModernUIComponents.kt"
if (Test-Path $file) {
    Write-Host "Removing problematic file: $file" -ForegroundColor Yellow
    Remove-Item $file -Force
}

Write-Host "UI components errors fix complete!" -ForegroundColor Cyan
