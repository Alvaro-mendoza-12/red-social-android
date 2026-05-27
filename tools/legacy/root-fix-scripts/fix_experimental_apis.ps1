# Script to remove ExperimentalMaterial3Api annotations from all Kotlin files
# This will help fix compilation errors

Get-ChildItem -Path "app\src\main\java\conectate\com\presentation" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Processing: $file"
    
    try {
        $content = Get-Content $file -Raw
        $newContent = $content -replace '@OptIn\(ExperimentalMaterial3Api::class\)', ''
        $newContent = $newContent -replace '@OptIn\(ExperimentalLayoutApi::class\)', ''
        $newContent = $newContent -replace '@OptIn\(ExperimentalPermissionsApi::class\)', ''
        
        Set-Content $file $newContent
        Write-Host "Fixed: $file" -ForegroundColor Green
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Experimental API removal complete!" -ForegroundColor Cyan
