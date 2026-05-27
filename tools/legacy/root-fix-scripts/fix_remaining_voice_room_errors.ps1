# Script to fix remaining voice room compilation errors
# This will remove remaining problematic voice room files

$problematicFiles = @(
    "app\src\main\java\conectate\com\presentation\voicerooms\RestrictedAccessScreen.kt",
    "app\src\main\java\conectate\com\presentation\voicerooms\RoomDetailScreen.kt"
)

foreach ($file in $problematicFiles) {
    $fullPath = Join-Path "d:\red social - copia" $file
    if (Test-Path $fullPath) {
        Write-Host "Removing problematic file: $fullPath" -ForegroundColor Yellow
        Remove-Item $fullPath -Force
    }
}

Write-Host "Remaining voice room errors fix complete!" -ForegroundColor Cyan
