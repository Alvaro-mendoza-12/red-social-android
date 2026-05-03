#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"

DEBUG_ONLY=false

if [[ "${1:-}" == "--clean" ]]; then
  ./gradlew --no-daemon --stacktrace clean
  shift
fi

if [[ "${1:-}" == "--debug-only" ]]; then
  DEBUG_ONLY=true
fi

./gradlew --no-daemon --stacktrace :app:qualityGateDebug
python tools/runtime_security_audit.py --root . --report build/reports/security/runtime_security_audit.md --json build/reports/security/runtime_security_audit.json --fail-threshold HIGH
python tools/security_sql_audit.py --root . --manifest sql/release-manifest.txt --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH

if [[ "${DEBUG_ONLY}" != "true" ]]; then
  ./gradlew --no-daemon --stacktrace :app:assembleRelease :app:bundleRelease
  python tools/performance_budget_check.py --artifacts app/build/outputs/apk/release app/build/outputs/bundle/release/app-release.aab --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json
fi
