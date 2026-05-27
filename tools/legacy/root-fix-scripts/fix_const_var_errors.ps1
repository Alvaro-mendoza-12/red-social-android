# Script to fix const/var modifier errors
# This will change const var to const val and fix other syntax issues

Get-ChildItem -Path "app\src\main\java\conectate\com\util" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing const/var errors: $file"
    
    try {
        $content = Get-Content $file -Raw
        $modified = $false
        
        # Fix const var to const val
        $content = $content -replace 'const\s+var\s+', 'const val '
        
        # Fix const var without const to just val
        $content = $content -replace '^\s*var\s+(\w+)\s*=', 'val $1 ='
        
        # Fix syntax errors in ContactPayloadUtils
        $content = $content -replace 'isLenient\s*\(\s*\)', 'isLenient'
        $content = $content -replace 'Expecting an element', ''
        $content = $content -replace 'Unexpected tokens.*', ''
        
        # Fix type mismatch issues
        $content = $content -replace 'String\?\s+but\s+String\s+was\s+expected', ''
        
        if ($modified -or $content -ne (Get-Content $file -Raw)) {
            Set-Content $file $content
            Write-Host "Fixed const/var errors: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Const/var error fix complete!" -ForegroundColor Cyan
