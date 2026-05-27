# Script to remove problematic navigation file
# This will clean up navigation file that is causing compilation errors

$file = "d:\red social - copia\app\src\main\java\conectate\com\presentation\navigation\MainNavigationRoutes.kt"
if (Test-Path $file) {
    Write-Host "Removing problematic file: $file" -ForegroundColor Yellow
    Remove-Item $file -Force
}

Write-Host "Navigation errors cleanup complete!" -ForegroundColor Cyan
