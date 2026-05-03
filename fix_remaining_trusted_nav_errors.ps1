# Script to fix remaining TrustedNavigationIntents and compilation errors
# This will remove references to deleted TrustedNavigationIntents class

Get-ChildItem -Path "app\src\main\java\conectate\com" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing remaining TrustedNavigationIntents errors: $file"
    
    try {
        $content = Get-Content $file -Raw
        $originalContent = $content
        
        # Remove TrustedNavigationIntents imports
        $content = $content -replace 'import conectate\.com\.util\.TrustedNavigationIntents', ''
        
        # Remove TrustedNavigationIntents usage
        $content = $content -replace 'TrustedNavigationIntents\.', ''
        
        # Fix VoiceRoomsScreen missing @Composable annotation
        if ($file -match 'VoiceRoomsScreen\.kt$') {
            $content = $content -replace '(fun\s+VoiceRoomsScreen)', '@Composable`n$1'
        }
        
        # Fix experimental API issues by adding @OptIn
        if ($content -match 'This material API is experimental') {
            $content = $content -replace '(fun\s+\w+[^{]*\{)', '@OptIn(ExperimentalMaterial3Api::class)`n$1'
        }
        
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
        
        # Fix return statement issues
        $content = $content -replace 'return\s+([^;]+);', 'return $1'
        
        # Fix type mismatch
        $content = $content -replace 'String\?\s+but\s+String\s+was\s+expected.*$', ''
        
        # Clean up error messages
        $content = $content -replace 'This material API is experimental.*$', ''
        
        if ($content -ne $originalContent) {
            Set-Content $file $content
            Write-Host "Fixed remaining TrustedNavigationIntents errors: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Remaining TrustedNavigationIntents error fix complete!" -ForegroundColor Cyan
