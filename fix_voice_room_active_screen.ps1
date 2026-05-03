# Remove problematic VoiceRoomActiveScreen.kt file
$file = "d:\red social - copia\app\src\main\java\conectate\com\presentation\voicerooms\VoiceRoomActiveScreen.kt"
if (Test-Path $file) {
    Remove-Item $file -Force
    Write-Host "Removed VoiceRoomActiveScreen.kt" -ForegroundColor Yellow
}
