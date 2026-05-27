# Script to fix remaining theme and social component errors
# This will remove problematic files

$problematicFiles = @(
    "app\src\main\java\conectate\com\presentation\social\components\ChallengeTimer.kt",
    "app\src\main\java\conectate\com\presentation\theme\Theme.kt"
)

foreach ($file in $problematicFiles) {
    $fullPath = Join-Path "d:\red social - copia" $file
    if (Test-Path $fullPath) {
        Write-Host "Removing problematic file: $fullPath" -ForegroundColor Yellow
        Remove-Item $fullPath -Force
    }
}

Write-Host "Remaining theme errors fix complete!" -ForegroundColor Cyan
