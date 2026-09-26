param([string]$JavaHome)
$ErrorActionPreference = 'Stop'
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixtureDir = Join-Path $PSScriptRoot 'fixtures/release-signing'
$wrapper = Join-Path $repoRoot 'gradlew.bat'
$fixtureKey = Join-Path $fixtureDir 'upload-key.fixture'
# Public placeholders, never real passwords. Only the configuration guard runs.
$complete = @(
    "-Pandroid.injected.signing.store.file=$fixtureKey",
    '-Pandroid.injected.signing.store.password= fixture with spaces ',
    '-Pandroid.injected.signing.key.alias=fixture',
    '-Pandroid.injected.signing.key.password= fixture with spaces '
)
$cases = @(
    @{Name='missing-signing-rejected'; Args=@(); Pass=$false; Message='Release signing is required'},
    @{Name='configured-cli-accepted'; Args=@('-PfixtureConfiguredSigning=true'); Pass=$true},
    @{Name='complete-ide-accepted'; Args=$complete; Pass=$true},
    @{Name='partial-ide-rejected'; Args=@($complete[0]); Pass=$false; Message='incompleta'},
    @{Name='partial-ide-does-not-fall-back-to-cli'; Args=@($complete[0],'-PfixtureConfiguredSigning=true'); Pass=$false; Message='incompleta'},
    @{Name='missing-ide-file-rejected'; Args=@("-Pandroid.injected.signing.store.file=$fixtureKey.missing")+$complete[1..3]; Pass=$false; Message='no existe'},
    @{Name='directory-is-not-keystore'; Args=@("-Pandroid.injected.signing.store.file=$fixtureDir")+$complete[1..3]; Pass=$false; Message='no existe'},
    @{Name='complete-ide-takes-precedence'; Args=$complete+@('-PfixtureConfiguredSigning=true'); Pass=$true}
)
foreach ($case in $cases) {
    $runArgs = @('-p', $fixtureDir, 'preReleaseBuild', '--offline', '--console=plain', '--max-workers=1') + $case.Args
    $output = & $wrapper @runArgs 2>&1 | Out-String
    $exitCode = $LASTEXITCODE
    $passed = ($exitCode -eq 0) -eq $case.Pass
    if ($case.Message) { $passed = $passed -and $output.Contains($case.Message) }
    if (-not $passed) { throw "Signing guard regression failed: $($case.Name) (exit $exitCode)." }
    Write-Output "PASS $($case.Name)"
}
Write-Output '8 signing-configuration regressions passed. No real keystore was unlocked or used to sign.'
