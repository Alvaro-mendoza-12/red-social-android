# Script to find the exact file causing NO_EXPECTED_TYPE error
# This will help identify the problematic code

Get-ChildItem -Path "app\src\main\java\conectate\com\presentation" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Checking: $file"
    
    try {
        $content = Get-Content $file -Raw
        
        # Look for patterns that might cause NO_EXPECTED_TYPE
        if ($content -match '@Annotated' -or $content -match 'ExperimentalMaterial3Api' -or $content -match 'ExperimentalLayoutApi') {
            Write-Host "Found experimental API in: $file" -ForegroundColor Yellow
            
            # Show lines with potential issues
            $lines = $content -split "`n"
            for ($i = 0; $i -lt $lines.Count; $i++) {
                if ($lines[$i] -match '@Annotated' -or $lines[$i] -match 'ExperimentalMaterial3Api' -or $lines[$i] -match 'ExperimentalLayoutApi') {
                    Write-Host "  Line $($i+1): $($lines[$i])" -ForegroundColor Red
                }
            }
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Search complete!" -ForegroundColor Cyan
