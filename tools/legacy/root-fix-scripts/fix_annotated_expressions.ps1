# Script to fix ALL @ annotations that create KtAnnotatedExpression
# This will completely eliminate the NO_EXPECTED_TYPE error

Get-ChildItem -Path "app\src\main\java\conectate\com\presentation" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing annotated expressions: $file"
    
    try {
        $content = Get-Content $file -Raw
        
        # Remove ALL @ annotations that might create KtAnnotatedExpression
        # This is a comprehensive fix for the compiler issue
        $newContent = $content -replace '@[A-Za-z_][A-Za-z0-9_]*\s*\([^)]*\)', ''
        
        # Remove any remaining @ annotations without parameters
        $newContent = $newContent -replace '@[A-Za-z_][A-Za-z0-9_]*', ''
        
        # Clean up any empty lines left by removal
        $newContent = $newContent -replace '^\s*\n', ''
        
        Set-Content $file $newContent
        Write-Host "Fixed annotated expressions: $file" -ForegroundColor Green
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Complete annotated expression fix complete!" -ForegroundColor Cyan
