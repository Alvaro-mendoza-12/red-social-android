# Script to remove ALL remaining experimental API references
# This will fix the compilation issues completely

Get-ChildItem -Path "app\src\main\java\conectate\com\presentation" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Processing: $file"
    
    try {
        $content = Get-Content $file -Raw
        
        # Remove ALL experimental API imports and annotations
        $newContent = $content -replace 'import androidx\.compose\.material3\.ExperimentalMaterial3Api', ''
        $newContent = $newContent -replace 'import androidx\.compose\.foundation\.layout\.ExperimentalLayoutApi', ''
        $newContent = $newContent -replace 'import androidx\.compose\.foundation\.ExperimentalFoundationApi', ''
        $newContent = $newContent -replace 'import androidx\.compose\.material\.ExperimentalMaterialApi', ''
        $newContent = $newContent -replace 'import com\.google\.accompanist\.permissions\.ExperimentalPermissionsApi', ''
        
        # Remove ALL @OptIn annotations with experimental APIs
        $newContent = $newContent -replace '@OptIn\([^)]*ExperimentalMaterial3Api[^)]*\)', ''
        $newContent = $newContent -replace '@OptIn\([^)]*ExperimentalLayoutApi[^)]*\)', ''
        $newContent = $newContent -replace '@OptIn\([^)]*ExperimentalFoundationApi[^)]*\)', ''
        $newContent = $newContent -replace '@OptIn\([^)]*ExperimentalMaterialApi[^)]*\)', ''
        $newContent = $newContent -replace '@OptIn\([^)]*ExperimentalPermissionsApi[^)]*\)', ''
        
        # Clean up empty @OptIn annotations
        $newContent = $newContent -replace '@OptIn\(\s*\)', ''
        
        # Clean up extra commas in @OptIn
        $newContent = $newContent -replace '@OptIn\(\s*,\s*([^)]+)\s*\)', '@OptIn($1)'
        
        Set-Content $file $newContent
        Write-Host "Fixed experimental APIs: $file" -ForegroundColor Green
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Complete experimental API removal complete!" -ForegroundColor Cyan
