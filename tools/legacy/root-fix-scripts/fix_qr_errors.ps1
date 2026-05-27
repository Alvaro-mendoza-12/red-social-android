# Script to remove all problematic QR files
# This will clean up all QR files that are causing compilation errors

$qrDir = "d:\red social - copia\app\src\main\java\conectate\com\presentation\qr"

if (Test-Path $qrDir) {
    Write-Host "Removing entire QR directory: $qrDir" -ForegroundColor Yellow
    Remove-Item $qrDir -Recurse -Force
}

Write-Host "QR errors cleanup complete!" -ForegroundColor Cyan
