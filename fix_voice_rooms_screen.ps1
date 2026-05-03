# Script to fix VoiceRoomsScreen compilation errors
# This will add missing @Composable annotations and fix parameter issues

$file = "d:\red social - copia\app\src\main\java\conectate\com\presentation\voicerooms\VoiceRoomsScreen.kt"

if (Test-Path $file) {
    Write-Host "Fixing VoiceRoomsScreen compilation errors: $file"
    
    $content = Get-Content $file -Raw
    
    # Add missing imports
    if ($content -notmatch 'import androidx\.compose\.foundation\.layout\.dp') {
        $content = $content -replace '(package .+`n)', "`$1`nimport androidx.compose.foundation.layout.dp`n"
    }
    
    if ($content -notmatch 'import androidx\.compose\.material3\.ExperimentalMaterial3Api') {
        $content = $content -replace '(package .+`n)', "`$1`nimport androidx.compose.material3.ExperimentalMaterial3Api`n"
    }
    
    # Fix function signatures to add missing parameters
    $content = $content -replace '(fun\s+VoiceRoomsScreen\([^)]*\))', 'fun VoiceRoomsScreen(navController: NavController, viewModel: VoiceRoomsViewModel = hiltViewModel())'
    
    # Fix lambda parameters
    $content = $content -replace 'onClick\s*=\s*\{\s*\}', 'onClick = { /* TODO */ }'
    $content = $content -replace 'isActive\s*=\s*\{\s*\}', 'isActive = { false }'
    $content = $content -replace 'onDelete\s*=\s*\{\s*\}', 'onDelete = { /* TODO */ }'
    
    # Fix room variable references
    $content = $content -replace '\{ room ->', '{ room ->'
    $content = $content -replace 'room\.', 'room.'
    
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
            
            if ($functionContent -match 'Text\(|Button\(|Column\(|Row\(|Card\(|Scaffold\(|TopAppBar\(|BottomSheetScaffold\(|ModalBottomSheet\(|FlowRow\(|FlowColumn\(') {
                if ($line -notmatch '@Composable') {
                    $line = "@Composable`n$line"
                }
            }
        }
        
        $newLines += $line
    }
    
    $content = $newLines -join "`n"
    
    # Fix experimental API issues
    $content = $content -replace '(fun\s+\w+[^{]*\{)', '@OptIn(ExperimentalMaterial3Api::class)`n$1'
    
    # Clean up error messages
    $content = $content -replace 'This material API is experimental.*$', ''
    
    Set-Content $file $content
    Write-Host "Fixed VoiceRoomsScreen compilation errors: $file" -ForegroundColor Green
} else {
    Write-Host "VoiceRoomsScreen.kt not found" -ForegroundColor Red
}

Write-Host "VoiceRoomsScreen fix complete!" -ForegroundColor Cyan
