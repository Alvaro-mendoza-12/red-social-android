# Script to fix remaining compilation errors
# This will address val reassignment, missing @Composable annotations, and other issues

Get-ChildItem -Path "app\src\main\java\conectate\com" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing remaining compilation errors: $file"
    
    try {
        $content = Get-Content $file -Raw
        $originalContent = $content
        
        # Fix val reassignment by changing val to var for reassigned variables
        $lines = $content -split "`n"
        $newLines = @()
        $variablesToChange = @{}
        
        # First pass: identify variables that need to be var
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i].Trim()
            if ($line -match '^\s*val\s+(\w+)\s*=') {
                $varName = $matches[1]
                # Look ahead for reassignment
                for ($j = $i + 1; $j -lt $lines.Count; $j++) {
                    if ($lines[$j] -match "$varName\s*=") {
                        $variablesToChange[$varName] = $true
                        break
                    }
                }
            }
        }
        
        # Second pass: apply changes
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            
            # Change val to var for variables that are reassigned
            foreach ($varName in $variablesToChange.Keys) {
                $line = $line -replace "^\s*val\s+$varName\s*=", "var $varName ="
            }
            
            $newLines += $line
        }
        
        $content = $newLines -join "`n"
        
        # Fix return statement issues
        $content = $content -replace 'return\s+([^;]+);', 'return $1'
        
        # Fix missing @Composable annotations
        if ($content -match 'Functions which invoke @Composable functions') {
            # Find functions that need @Composable annotation
            $content = $content -replace '(fun\s+\w+[^{]*\{)', '@Composable`n$1'
        }
        
        # Fix experimental API issues
        $content = $content -replace 'This material API is experimental.*$', ''
        
        # Fix ContactPayloadUtils syntax errors
        $content = $content -replace 'isLenient\s*\(\s*\)', '.isLenient()'
        $content = $content -replace 'Expecting an element.*$', ''
        $content = $content -replace 'Unexpected tokens.*$', ''
        
        # Fix type mismatch
        $content = $content -replace 'String\?\s+but\s+String\s+was\s+expected.*$', ''
        
        # Fix const var issues
        $content = $content -replace 'const\s+var\s+', 'const val '
        
        if ($content -ne $originalContent) {
            Set-Content $file $content
            Write-Host "Fixed remaining errors: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Remaining compilation error fix complete!" -ForegroundColor Cyan
