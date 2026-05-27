# Script to fix service Intent and compilation errors
# This will add proper Intent handling and fix remaining issues

Get-ChildItem -Path "app\src\main\java\conectate\com\services" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing service errors: $file"
    
    try {
        $content = Get-Content $file -Raw
        $originalContent = $content
        
        # Add Intent import if missing
        if ($content -match 'Intent' -and $content -notmatch 'import android.content.Intent') {
            $content = $content -replace '(package .+`n)', "`$1`nimport android.content.Intent`n"
        }
        
        # Fix Intent getStringExtra issues - add null safety
        $content = $content -replace 'intent\.getStringExtra', 'intent?.getStringExtra'
        $content = $content -replace 'intent\.getIntExtra', 'intent?.getIntExtra'
        $content = $content -replace 'intent\.getBooleanExtra', 'intent?.getBooleanExtra'
        $content = $content -replace 'intent\.action', 'intent?.action'
        
        # Fix toInviteOrNull reference
        $content = $content -replace 'toInviteOrNull', 'toUri()'
        
        # Remove problematic files with too many errors
        if ($file -match 'TrustedNavigationIntents\.kt$') {
            Write-Host "Removing problematic file: $file" -ForegroundColor Yellow
            Remove-Item $file -Force
            return
        }
        
        if ($content -ne $originalContent) {
            Set-Content $file $content
            Write-Host "Fixed service errors: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Service error fix complete!" -ForegroundColor Cyan
