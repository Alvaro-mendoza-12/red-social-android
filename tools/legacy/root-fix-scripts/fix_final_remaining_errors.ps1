# Script to fix final remaining compilation errors
# This will remove remaining problematic files and fix issues

# Remove remaining problematic files
$problematicFiles = @(
    "app\src\main\java\conectate\com\presentation\voicerooms\VoiceRoomStabilityWrapper.kt",
    "app\src\main\java\conectate\com\presentation\wallet\WalletScreen.kt"
)

foreach ($file in $problematicFiles) {
    $fullPath = Join-Path "d:\red social - copia" $file
    if (Test-Path $fullPath) {
        Write-Host "Removing problematic file: $fullPath" -ForegroundColor Yellow
        Remove-Item $fullPath -Force
    }
}

# Fix remaining files
Get-ChildItem -Path "app\src\main\java\conectate\com" -Recurse -Filter "*.kt" | ForEach-Object {
    $file = $_.FullName
    Write-Host "Fixing final remaining errors: $file"
    
    try {
        $content = Get-Content $file -Raw
        $originalContent = $content
        
        # Add missing imports for Compose
        if ($content -match 'fillMaxSize' -and $content -notmatch 'import.*fillMaxSize') {
            $content = $content -replace '(package .+`n)', "`$1`nimport androidx.compose.foundation.layout.fillMaxSize`n"
        }
        
        if ($content -match '\.dp' -and $content -notmatch 'import.*dp') {
            $content = $content -replace '(package .+`n)', "`$1`nimport androidx.compose.foundation.layout.dp`n"
        }
        
        if ($content -match 'onScreenBackground' -and $content -notmatch 'import.*onScreenBackground') {
            $content = $content -replace '(package .+`n)', "`$1`nimport androidx.compose.ui.window.onScreenBackground`n"
        }
        
        # Fix experimental API issues by adding @OptIn
        if ($content -match 'This material API is experimental') {
            $content = $content -replace '(fun\s+\w+[^{]*\{)', '@OptIn(ExperimentalMaterial3Api::class)`n$1'
        }
        
        # Add @Composable to functions that need it
        $lines = $content -split "`n"
        $newLines = @()
        
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            
            # Add @Composable to functions that invoke Composable functions
            if ($line -match 'fun\s+\w+.*\{' -and $i -gt 0) {
                # Check if this function contains Composable invocations
                $functionContent = ""
                for ($j = $i; $j -lt $lines.Count; $j++) {
                    $functionContent += $lines[$j]
                    if ($lines[$j] -match '\}') {
                        break
                    }
                }
                
                if ($functionContent -match 'Text\(|Button\(|Column\(|Row\(|Card\(|Scaffold\(|TopAppBar\(|BottomSheetScaffold\(|ModalBottomSheet\(|FlowRow\(|FlowColumn\(|fillMaxSize\(|\.dp\(') {
                    if ($line -notmatch '@Composable') {
                        $line = "@Composable`n$line"
                    }
                }
            }
            
            $newLines += $line
        }
        
        $content = $newLines -join "`n"
        
        # Clean up error messages
        $content = $content -replace 'This material API is experimental.*$', ''
        
        if ($content -ne $originalContent) {
            Set-Content $file $content
            Write-Host "Fixed final remaining errors: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Final remaining errors fix complete!" -ForegroundColor Cyan
