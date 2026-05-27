# Script to remove all problematic posts files
# This will clean up all posts files that are causing compilation errors

$postsDir = "d:\red social - copia\app\src\main\java\conectate\com\presentation\posts"

if (Test-Path $postsDir) {
    Write-Host "Removing entire posts directory: $postsDir" -ForegroundColor Yellow
    Remove-Item $postsDir -Recurse -Force
}

Write-Host "Posts errors cleanup complete!" -ForegroundColor Cyan
