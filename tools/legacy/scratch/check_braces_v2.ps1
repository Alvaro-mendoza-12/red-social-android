
param($FilePath)
$content = Get-Content $FilePath
$stack = New-Object System.Collections.Generic.Stack[int]
$lineNum = 0
foreach ($line in $content) {
    $lineNum++
    for ($i = 0; $i -lt $line.Length; $i++) {
        if ($line[$i] -eq '{') {
            $stack.Push($lineNum)
        } elseif ($line[$i] -eq '}') {
            if ($stack.Count -eq 0) {
                Write-Host "Extra closing brace at line $lineNum, column $($i+1)"
            } else {
                [void]$stack.Pop()
            }
        }
    }
}
while ($stack.Count -gt 0) {
    Write-Host "Unclosed brace from line $($stack.Pop())"
}
