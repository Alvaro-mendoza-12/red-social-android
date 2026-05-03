# Script to fix remaining compilation errors
# This will fix val reassignment, return statements, and missing imports

Get-ChildItem -Path "app\src\main\java\conectate\com" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing remaining errors: $file"
    
    try {
        $content = Get-Content $file -Raw
        $modified = $false
        
        # Fix val reassignment issues
        $content = $content -replace 'val\s+(\w+)\s*=', 'var $1 ='
        
        # Fix return statements in wrong places
        $content = $content -replace 'return\s+([^;]+);', 'return $1'
        
        # Fix type mismatches for String? to String
        $content = $content -replace 'String\?\s+but\s+String\s+was\s+expected', ''
        
        # Fix missing dp import
        if ($content -match '\.dp' -and $content -notmatch 'import.*foundation\.layout\.dp') {
            $content = $content -replace '(package .+`n)', "`$1`nimport androidx.compose.foundation.layout.dp`n"
            $modified = $true
        }
        
        # Fix experimental API issues by adding @OptIn
        if ($content -match 'experimental.*is likely to change') {
            $content = $content -replace '(fun\s+\w+)', '@OptIn(ExperimentalMaterial3Api::class)`n$1'
            $modified = $true
        }
        
        if ($modified -or $content -ne (Get-Content $file -Raw)) {
            Set-Content $file $content
            Write-Host "Fixed errors: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Remaining errors fix complete!" -ForegroundColor Cyan
