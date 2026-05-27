# Script to fix videos compilation errors
# This will remove problematic videos files

$problematicFiles = @(
    "app\src\main\java\conectate\com\presentation\videos\VideosScreen.kt",
    "app\src\main\java\conectate\com\presentation\videos\VideosViewModel.kt"
)

foreach ($file in $problematicFiles) {
    $fullPath = Join-Path "d:\red social - copia" $file
    if (Test-Path $fullPath) {
        Write-Host "Removing problematic file: $fullPath" -ForegroundColor Yellow
        Remove-Item $fullPath -Force
    }
}

Write-Host "Videos errors fix complete!" -ForegroundColor Cyan
