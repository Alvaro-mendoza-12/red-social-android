#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"

if [[ "${1:-}" == "--clean" ]]; then
  ./gradlew --no-daemon --stacktrace clean
fi

./gradlew --no-daemon --stacktrace :app:qualityGateDebug
python tools/security_sql_audit.py --root . --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH
