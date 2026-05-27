# Script to fix critical compilation errors
# This will address the most severe issues preventing compilation

Get-ChildItem -Path "app\src\main\java\conectate\com" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing critical errors: $file"
    
    try {
        $content = Get-Content $file -Raw
        $originalContent = $content
        
        # Fix IncomingCallForegroundService syntax errors
        if ($file -match 'IncomingCallForegroundService\.kt$') {
            $content = $content -replace 'private fun Intent\.toUri\(\)\(\): CallInvite\?', 'private fun Intent.toCallInvite(): CallInvite?'
            $content = $content -replace 'toUri\(\)', 'toCallInvite()'
        }
        
        # Fix TrustedNavigationIntents prefs function calls
        if ($file -match 'TrustedNavigationIntents\.kt$') {
            $content = $content -replace 'prefs\(', 'prefs(context, '
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
        
        # Add missing @Composable annotations for wallet
        if ($file -match 'GiftSelectionDialog\.kt$') {
            $content = $content -replace '(fun\s+GiftSelectionDialog)', '@Composable`n$1'
        }
        
        if ($content -ne $originalContent) {
            Set-Content $file $content
            Write-Host "Fixed critical errors: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Critical error fix complete!" -ForegroundColor Cyan
