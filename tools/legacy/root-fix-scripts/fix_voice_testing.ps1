# Script to fix the voice testing compilation errors
# This will add missing imports and fix reference issues

Get-ChildItem -Path "app\src\main\java\conectate\com\voice\testing" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing voice testing file: $file"
    
    try {
        $content = Get-Content $file -Raw
        
        # Add missing imports at the top
        $importsToAdd = @(
            "import org.junit.Test",
            "import org.junit.Assert.*",
            "import kotlin.math.PI",
            "import kotlin.math.sin"
        )
        
        # Check if imports already exist
        foreach ($import in $importsToAdd) {
            if ($content -notmatch [regex]::Escape($import)) {
                # Add import after package declaration
                $content = $content -replace "(package .+`n)", "`$1`n$import`n"
            }
        }
        
        # Fix specific issues
        $content = $content -replace 'kotlin\.test\.', 'org.junit.Assert.'
        
        # Fix type mismatches
        $content = $content -replace 'FloatArray\(\)', 'DoubleArray()'
        $content = $content -replace 'FloatArray', 'DoubleArray'
        
        # Fix Long literal issues
        $content = $content -replace '(\d+)\.0L', '${1}L'
        
        Set-Content $file $content
        Write-Host "Fixed voice testing: $file" -ForegroundColor Green
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Voice testing fix complete!" -ForegroundColor Cyan
