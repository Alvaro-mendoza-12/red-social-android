# Script to remove @Annotated annotations that cause compiler issues
# This will fix the NO_EXPECTED_TYPE error

Get-ChildItem -Path "app\src\main\java\conectate\com\presentation" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Processing: $file"
    
    try {
        $content = Get-Content $file -Raw
        
        # Remove @Annotated annotations that cause compiler issues
        $newContent = $content -replace '@Annotated\s*\([^)]*\)', ''
        
        # Also remove any remaining @Annotated without parameters
        $newContent = $newContent -replace '@Annotated', ''
        
        Set-Content $file $newContent
        Write-Host "Fixed @Annotated: $file" -ForegroundColor Green
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "@Annotated removal complete!" -ForegroundColor Cyan
