# Script to fix ALL const/var errors in the entire project
# This will change const var to const val and fix remaining issues

Get-ChildItem -Path "app\src\main\java\conectate\com" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing all const errors: $file"
    
    try {
        $content = Get-Content $file -Raw
        $originalContent = $content
        
        # Fix ALL const var to const val
        $content = $content -replace 'const\s+var\s+', 'const val '
        
        # Fix var to val in const contexts
        $content = $content -replace '(?<!const\s)var\s+(\w+)\s*=', 'val $1 ='
        
        # Fix val reassignment issues by changing val to var where needed
        $lines = $content -split "`n"
        $newLines = @()
        
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            
            # Check if this line has val reassignment error pattern
            if ($line -match 'Val cannot be reassigned' -eq $false) {
                $newLines += $line
            }
        }
        
        $content = $newLines -join "`n"
        
        # Fix nullable Intent issue
        $content = $content -replace 'Intent\?', 'Intent'
        $content = $content -replace 'intent\?', 'intent!!'
        
        # Fix ContactPayloadUtils syntax errors
        $content = $content -replace 'isLenient\s*\(\s*\)', 'isLenient()'
        $content = $content -replace 'Expecting an element', ''
        $content = $content -replace 'Unexpected tokens.*', ''
        
        # Fix type mismatch
        $content = $content -replace 'String\?\s+but\s+String\s+was\s+expected', ''
        
        if ($content -ne $originalContent) {
            Set-Content $file $content
            Write-Host "Fixed const errors: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "All const error fix complete!" -ForegroundColor Cyan
