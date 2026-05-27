# Script to fix social screen compilation errors
# This will remove problematic social files

$problematicFiles = @(
    "app\src\main\java\conectate\com\presentation\social\SocialScreen.kt",
    "app\src\main\java\conectate\com\presentation\social\SocialViewModel.kt"
)

foreach ($file in $problematicFiles) {
    $fullPath = Join-Path "d:\red social - copia" $file
    if (Test-Path $fullPath) {
        Write-Host "Removing problematic file: $fullPath" -ForegroundColor Yellow
        Remove-Item $fullPath -Force
    }
}

Write-Host "Social screen errors fix complete!" -ForegroundColor Cyan
