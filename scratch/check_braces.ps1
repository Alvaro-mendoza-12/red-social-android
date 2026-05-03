
$content = Get-Content "d:\red social - copia\app\src\main\java\conectate\com\data\repository\PostRepository.kt"
$stack = @()
$lineNum = 0
foreach ($line in $content) {
    $lineNum++
    for ($i = 0; $i -lt $line.Length; $i++) {
        if ($line[$i] -eq '{') {
            $stack += $lineNum
        } elseif ($line[$i] -eq '}') {
            if ($stack.Count -eq 0) {
                Write-Host "Extra closing brace at line $lineNum"
            } else {
                $stack = $stack[0..($stack.Count - 2)]
            }
        }
    }
}
foreach ($openLine in $stack) {
    Write-Host "Unclosed brace from line $openLine"
}
