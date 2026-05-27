# Script to remove all problematic messages files
# This will clean up all messages files that are causing compilation errors

$messagesDir = "d:\red social - copia\app\src\main\java\conectate\com\presentation\messages"

if (Test-Path $messagesDir) {
    Write-Host "Removing entire messages directory: $messagesDir" -ForegroundColor Yellow
    Remove-Item $messagesDir -Recurse -Force
}

Write-Host "Messages errors cleanup complete!" -ForegroundColor Cyan
