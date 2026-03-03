param(
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

Set-Location -Path (Join-Path $PSScriptRoot "..")

if ($Clean) {
    .\gradlew.bat --no-daemon --stacktrace clean
}

.\gradlew.bat --no-daemon --stacktrace :app:qualityGateDebug
python tools/security_sql_audit.py --root . --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH
