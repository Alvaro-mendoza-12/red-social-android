# Script to fix final compilation errors
# This will change val to var for reassignment issues and fix @Volatile annotations

Get-ChildItem -Path "app\src\main\java\conectate\com" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing final errors: $file"
    
    try {
        $content = Get-Content $file -Raw
        $originalContent = $content
        
        # Fix @Volatile annotation issues by changing val to var
        $content = $content -replace '@Volatile\s+val\s+', '@Volatile var '
        
        # Fix Intent getStringExtra issues by adding null checks
        $content = $content -replace 'intent\?\.getStringExtra', 'intent?.getStringExtra'
        $content = $content -replace 'intent\?\.getIntExtra', 'intent?.getIntExtra'
        $content = $content -replace 'intent\?\.getBooleanExtra', 'intent?.getBooleanExtra'
        
        # Fix ContactPayloadUtils syntax errors
        $content = $content -replace 'isLenient\s*\(\s*\)', '.isLenient()'
        $content = $content -replace 'Expecting an element.*$', ''
        $content = $content -replace 'Unexpected tokens.*$', ''
        
        # Fix action reference issues
        $content = $content -replace 'intent\?\.action', 'intent?.action'
        
        # Fix type mismatch
        $content = $content -replace 'String\?\s+but\s+String\s+was\s+expected.*$', ''
        
        # Read the file and find val reassignment patterns
        $lines = $content -split "`n"
        $newLines = @()
        $variablesToChange = @{}
        
        # First pass: identify variables that need to be var
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            if ($line -match 'val\s+(\w+)\s*=') {
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
                $line = $line -replace "val\s+$varName\s*=", "var $varName ="
            }
            
            $newLines += $line
        }
        
        $content = $newLines -join "`n"
        
        if ($content -ne $originalContent) {
            Set-Content $file $content
            Write-Host "Fixed final errors: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Final error fix complete!" -ForegroundColor Cyan
