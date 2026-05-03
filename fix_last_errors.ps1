# Script to fix final compilation errors
# This will address the most critical remaining issues

Get-ChildItem -Path "app\src\main\java\conectate\com" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing last errors: $file"
    
    try {
        $content = Get-Content $file -Raw
        $originalContent = $content
        
        # Fix IncomingCallForegroundService toCallInvite reference
        if ($file -match 'IncomingCallForegroundService\.kt$') {
            $content = $content -replace 'toCallInvite\(\)', 'toUri()'
        }
        
        # Fix TrustedNavigationIntents context parameter issues
        if ($file -match 'TrustedNavigationIntents\.kt$') {
            # Remove duplicate context parameters
            $content = $content -replace 'context: Context,\s*context: Context', 'context: Context'
            # Fix missing context type
            $content = $content -replace 'value-parameter context: \[Error type: Missed a type for a value parameter context\]', 'context: Context'
        }
        
        # Fix ContactPayloadUtils syntax errors
        $content = $content -replace 'isLenient\s*\(\s*\)', '.isLenient()'
        $content = $content -replace 'Expecting an element.*$', ''
        $content = $content -replace 'Unexpected tokens.*$', ''
        
        # Fix val reassignment by changing val to var
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
        
        # Fix type mismatch
        $content = $content -replace 'String\?\s+but\s+String\s+was\s+expected.*$', ''
        
        # Fix experimental API issues
        $content = $content -replace 'This material API is experimental.*$', ''
        
        if ($content -ne $originalContent) {
            Set-Content $file $content
            Write-Host "Fixed last errors: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Last error fix complete!" -ForegroundColor Cyan
