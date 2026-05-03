param(
    [switch]$Clean,
    [switch]$DebugOnly
)

$ErrorActionPreference = "Stop"

Set-Location -Path (Join-Path $PSScriptRoot "..")

if ($Clean) {
    .\gradlew.bat --no-daemon --stacktrace clean
}

.\gradlew.bat --no-daemon --stacktrace :app:qualityGateDebug
python tools/runtime_security_audit.py --root . --report build/reports/security/runtime_security_audit.md --json build/reports/security/runtime_security_audit.json --fail-threshold HIGH
python tools/security_sql_audit.py --root . --manifest sql/release-manifest.txt --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH

if (-not $DebugOnly) {
    .\gradlew.bat --no-daemon --stacktrace :app:assembleRelease :app:bundleRelease
    python tools/performance_budget_check.py --artifacts app/build/outputs/apk/release app/build/outputs/bundle/release/app-release.aab --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json
}
