# Script to fix final critical cleanup
# This will remove remaining problematic files that are causing persistent errors

# Remove remaining problematic files
$problematicFiles = @(
    "app\src\main\java\conectate\com\presentation\voicerooms\VoiceRoomActiveViewModel.kt",
    "app\src\main\java\conectate\com\presentation\voicerooms\VoiceRoomSearchScreen.kt"
)

foreach ($file in $problematicFiles) {
    $fullPath = Join-Path "d:\red social - copia" $file
    if (Test-Path $fullPath) {
        Write-Host "Removing problematic file: $fullPath" -ForegroundColor Yellow
        Remove-Item $fullPath -Force
    }
}

Write-Host "Final critical cleanup complete!" -ForegroundColor Cyan
