# Script to fix @Composable function errors
# This will add missing @Composable annotations and fix experimental API issues

Get-ChildItem -Path "app\src\main\java\conectate\com\presentation" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing Composable errors: $file"
    
    try {
        $content = Get-Content $file -Raw
        $modified = $false
        
        # Add @Composable to functions that invoke Composable functions
        $lines = $content -split "`n"
        $newLines = @()
        
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            
            # Check if this is a function definition that needs @Composable
            if ($line -match '^\s*fun\s+\w+\s*\(' -and $i -gt 0) {
                # Look ahead for Composable invocations in the function body
                $needsComposable = $false
                $braceCount = 0
                $inFunction = $false
                
                for ($j = $i; $j -lt $lines.Count; $j++) {
                    $currentLine = $lines[$j]
                    
                    # Count braces to determine function scope
                    $braceCount += ($currentLine.ToCharArray() | Where-Object { $_ -eq '{' }).Count
                    $braceCount -= ($currentLine.ToCharArray() | Where-Object { $_ -eq '}' }).Count
                    
                    # Check for Composable invocations
                    if ($currentLine -match 'Text\(|Card\(|Column\(|Row\(|Button\(|Spacer\(|Modifier\.|MaterialTheme\.|collectAsState\(') {
                        $needsComposable = $true
                        break
                    }
                    
                    # Exit when function ends
                    if ($braceCount -eq 0 -and $j -gt $i) {
                        break
                    }
                }
                
                if ($needsComposable -and $line -notmatch '@Composable') {
                    $newLines += "@Composable"
                    $newLines += $line
                    $modified = $true
                } else {
                    $newLines += $line
                }
            } else {
                $newLines += $line
            }
        }
        
        if ($modified) {
            $newContent = $newLines -join "`n"
            Set-Content $file $newContent
            Write-Host "Fixed Composable annotations: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Composable error fix complete!" -ForegroundColor Cyan
