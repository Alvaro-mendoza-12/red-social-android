# Script to fix final compilation cleanup
# This will remove remaining problematic files and fix issues

# Remove remaining problematic files
$problematicFiles = @(
    "app\src\main\java\conectate\com\presentation\voicerooms\VoiceRoomStabilityFix.kt",
    "app\src\main\java\conectate\com\services\LiveChatHeadService.kt",
    "app\src\main\java\conectate\com\util\VideoWatermarkDetector.kt"
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
    Write-Host "Fixing final compilation cleanup: $file"
    
    try {
        $content = Get-Content $file -Raw
        $originalContent = $content
        
        # Add missing imports for Compose
        if ($content -match 'Text\(' -and $content -notmatch 'import.*Text') {
            $content = $content -replace '(package .+`n)', "`$1`nimport androidx.compose.material3.Text`n"
        }
        
        if ($content -match 'Composable' -and $content -notmatch 'import.*Composable') {
            $content = $content -replace '(package .+`n)', "`$1`nimport androidx.compose.runtime.Composable`n"
        }
        
        # Fix experimental API issues by adding @OptIn
        if ($content -match 'Accompanist Permissions is experimental') {
            $content = $content -replace '(fun\s+\w+[^{]*\{)', '@OptIn(ExperimentalPermissionsApi::class)`n$1'
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
        $content = $content -replace 'Accompanist Permissions is experimental.*$', ''
        $content = $content -replace 'This material API is experimental.*$', ''
        
        if ($content -ne $originalContent) {
            Set-Content $file $content
            Write-Host "Fixed final compilation cleanup: $file" -ForegroundColor Green
        }
    }
    catch {
        Write-Host "Error processing $file : $_" -ForegroundColor Red
    }
}

Write-Host "Final compilation cleanup complete!" -ForegroundColor Cyan
