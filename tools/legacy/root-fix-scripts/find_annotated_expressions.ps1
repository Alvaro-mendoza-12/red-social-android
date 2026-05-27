# Script to find KtAnnotatedExpression that causes NO_EXPECTED_TYPE error
# This will identify the exact problematic code

Get-ChildItem -Path "app\src\main\java\conectate\com\presentation" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Checking for annotated expressions: $file"
    
    try {
        $content = Get-Content $file -Raw
        
        # Look for patterns that might create KtAnnotatedExpression
        if ($content -match '@[A-Za-z]' -and $content -match '\(.*\)') {
            Write-Host "Found potential annotated expression in: $file" -ForegroundColor Yellow
            
            # Show lines with @ annotations followed by parentheses
            $lines = $content -split "`n"
            for ($i = 0; $i -lt $lines.Count; $i++) {
                $line = $lines[$i]
                if ($line -match '@[A-Za-z]' -and ($i + 1) -lt $lines.Count -and $lines[$i + 1] -match '^\s*\(') {
                    Write-Host "  Line $($i+1): $($line)" -ForegroundColor Red
                    Write-Host "  Line $($i+2): $($lines[$i+1])" -ForegroundColor Red
                }
            }
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Annotated expression search complete!" -ForegroundColor Cyan
