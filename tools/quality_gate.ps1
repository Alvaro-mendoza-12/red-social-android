param(
    [switch]$Clean,
    [switch]$DebugOnly,
    [switch]$ToolingOnly
)

$ErrorActionPreference = "Stop"

function Get-JavaMajorVersion([string]$JavaHome) {
    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        return $null
    }

    $javaExecutable = Join-Path $JavaHome "bin\java.exe"
    if (-not (Test-Path -LiteralPath $javaExecutable)) {
        return $null
    }

    # Java intentionally writes `-version` to stderr. PowerShell 7 promotes
    # native stderr to an ErrorRecord, so the script's strict error policy
    # would abort before the compatible JDK can be selected.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $versionText = (& $javaExecutable -version 2>&1 | ForEach-Object { $_.ToString() } | Out-String)
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($versionText -notmatch 'version "([^" ]+)') {
        return $null
    }

    $version = $Matches[1]
    $majorPart = ($version -split '\.')[0]
    if ($majorPart -eq "1") {
        $majorPart = ($version -split '\.')[1]
    }
    $major = 0
    if ([int]::TryParse($majorPart, [ref]$major)) {
        return $major
    }
    return $null
}

if (-not $ToolingOnly) {
    $configuredJavaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME")
    $configuredJavaMajor = Get-JavaMajorVersion $configuredJavaHome
    if ($null -eq $configuredJavaMajor -or $configuredJavaMajor -lt 17 -or $configuredJavaMajor -gt 21) {
        $candidateJavaHomes = [System.Collections.Generic.List[string]]::new()
        foreach ($candidate in @(
            [Environment]::GetEnvironmentVariable("ANDROID_STUDIO_JAVA_HOME"),
            [Environment]::GetEnvironmentVariable("JDK_HOME"),
            (Join-Path $env:ProgramFiles "Android\Android Studio\jbr")
        )) {
            if (-not [string]::IsNullOrWhiteSpace($candidate)) {
                $candidateJavaHomes.Add($candidate)
            }
        }

        $userJdksRoot = Join-Path $env:USERPROFILE ".jdks"
        if (Test-Path -LiteralPath $userJdksRoot) {
            Get-ChildItem -LiteralPath $userJdksRoot -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '^(jbr|jdk)-' } |
                Sort-Object LastWriteTime -Descending |
                ForEach-Object { $candidateJavaHomes.Add($_.FullName) }
        }

        $compatibleJavaHome = $candidateJavaHomes |
            Select-Object -Unique |
            Where-Object {
                $candidateMajor = Get-JavaMajorVersion $_
                $candidateMajor -ne $null -and $candidateMajor -ge 17 -and $candidateMajor -le 21
            } |
            Select-Object -First 1

        if ($compatibleJavaHome) {
            $env:JAVA_HOME = $compatibleJavaHome
            $env:Path = "$compatibleJavaHome\bin;$env:Path"
            Write-Host "Using compatible JDK $((Get-JavaMajorVersion $compatibleJavaHome)) from $compatibleJavaHome"
        } else {
            throw "NOVVIN Android requires JDK 17-21; set JAVA_HOME, ANDROID_STUDIO_JAVA_HOME, or install Android Studio JBR 21."
        }
    }
}

Set-Location -Path (Join-Path $PSScriptRoot "..")

# Keep Gradle caches and temporary transform artifacts on the workspace drive.
# This avoids false build failures when the Windows system drive is small while
# the project drive has sufficient capacity. Both paths are gitignored.
$workspaceRoot = (Get-Location).Path
$env:GRADLE_USER_HOME = Join-Path $workspaceRoot ".gradle-user-home"
$qualityGateTemp = Join-Path $workspaceRoot "tmp\quality-gate"
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $qualityGateTemp | Out-Null
$env:TEMP = $qualityGateTemp
$env:TMP = $qualityGateTemp
$env:PYTHONDONTWRITEBYTECODE = "1"

$pythonExecutable = if ([string]::IsNullOrWhiteSpace($env:NOVVIN_PYTHON)) { "python" } else { $env:NOVVIN_PYTHON }
try {
    & $pythonExecutable -c "import sys; assert sys.version_info >= (3, 8)" 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Python is unavailable or older than 3.8" }
} catch {
    throw "A working Python 3.8+ is required. Install Python or set NOVVIN_PYTHON to its python.exe path."
}

$pythonEnvironment = Join-Path $workspaceRoot "build\quality-gate-python"
$qualityGatePython = Join-Path $pythonEnvironment "Scripts\python.exe"
if (-not (Test-Path -LiteralPath $qualityGatePython)) {
    & $pythonExecutable -m venv $pythonEnvironment
    if ($LASTEXITCODE -ne 0) {
        throw "Python virtual environment creation failed with exit code $LASTEXITCODE"
    }
}

function Assert-NativeSuccess([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

if ($ToolingOnly -and $Clean) {
    throw "-ToolingOnly cannot be combined with -Clean."
}

if ($Clean) {
    .\gradlew.bat --no-daemon --stacktrace clean
    Assert-NativeSuccess "Gradle clean"
}

if (-not $ToolingOnly) {
    .\gradlew.bat --no-daemon --stacktrace :app:qualityGateDebug
    Assert-NativeSuccess "Android debug quality gate"
}
& $qualityGatePython -m pip install -r tools/requirements.txt
Assert-NativeSuccess "Release tooling Python dependencies"
& $qualityGatePython -m unittest discover -s tools/tests -p "test_*.py"
Assert-NativeSuccess "Release tooling unit tests"
& $qualityGatePython tools/runtime_security_audit.py --root . --report build/reports/security/runtime_security_audit.md --json build/reports/security/runtime_security_audit.json --fail-threshold HIGH
Assert-NativeSuccess "Runtime security audit"
& $qualityGatePython tools/security_sql_audit.py --root . --manifest sql/release-manifest.txt --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH
Assert-NativeSuccess "SQL security audit"

if (-not $DebugOnly -and -not $ToolingOnly) {
    .\gradlew.bat --no-daemon --stacktrace :app:assembleRelease :app:bundleRelease
    Assert-NativeSuccess "Android release build"
    & $qualityGatePython tools/verify_r8_release.py --report build/reports/release/r8-release-verification.md --json build/reports/release/r8-release-verification.json
    Assert-NativeSuccess "R8 release entry-point verification"
    & $qualityGatePython tools/verify_android_release.py app/build/outputs/bundle/release/app-release.aab
    Assert-NativeSuccess "Android release signature verification"
    & $qualityGatePython tools/verify_android_page_size.py app/build/outputs/bundle/release/app-release.aab
    Assert-NativeSuccess "Android 16 KiB page-size verification"
    & $qualityGatePython tools/performance_budget_check.py --artifacts app/build/outputs/bundle/release/app-release.aab --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json
    Assert-NativeSuccess "Android performance budget"
}
