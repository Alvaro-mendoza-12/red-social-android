#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"
export PYTHONDONTWRITEBYTECODE=1

DEBUG_ONLY=false
TOOLING_ONLY=false
CLEAN=false

if [[ "${1:-}" == "--clean" ]]; then
  CLEAN=true
  shift
fi

if [[ "${1:-}" == "--debug-only" ]]; then
  DEBUG_ONLY=true
  shift
elif [[ "${1:-}" == "--tooling-only" ]]; then
  TOOLING_ONLY=true
  shift
fi

if [[ $# -gt 0 ]]; then
  echo "Unknown option: $1. Use --clean, --debug-only, or --tooling-only." >&2
  exit 2
fi

if [[ "${CLEAN}" == "true" && "${TOOLING_ONLY}" == "true" ]]; then
  echo "--clean cannot be combined with --tooling-only" >&2
  exit 2
fi
if [[ "${CLEAN}" == "true" ]]; then
  ./gradlew --no-daemon --stacktrace clean
fi

if [[ "${TOOLING_ONLY}" != "true" ]]; then
  ./gradlew --no-daemon --stacktrace :app:qualityGateDebug
fi
python3 -m venv build/quality-gate-python-unix
PYTHON="build/quality-gate-python-unix/bin/python"
"${PYTHON}" -m pip install -r tools/requirements.txt
"${PYTHON}" -m unittest discover -s tools/tests -p 'test_*.py'
"${PYTHON}" tools/runtime_security_audit.py --root . --report build/reports/security/runtime_security_audit.md --json build/reports/security/runtime_security_audit.json --fail-threshold HIGH
"${PYTHON}" tools/security_sql_audit.py --root . --manifest sql/release-manifest.txt --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH

if [[ "${DEBUG_ONLY}" != "true" && "${TOOLING_ONLY}" != "true" ]]; then
  ./gradlew --no-daemon --stacktrace :app:assembleRelease :app:bundleRelease
  "${PYTHON}" tools/verify_r8_release.py --report build/reports/release/r8-release-verification.md --json build/reports/release/r8-release-verification.json
  "${PYTHON}" tools/verify_android_release.py app/build/outputs/bundle/release/app-release.aab
  "${PYTHON}" tools/verify_android_page_size.py app/build/outputs/bundle/release/app-release.aab
  "${PYTHON}" tools/performance_budget_check.py --artifacts app/build/outputs/bundle/release/app-release.aab --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json
fi
