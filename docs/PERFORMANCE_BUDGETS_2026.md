# Performance Budgets (2026)

## Scope
Release artifact budgets for Android APK packaging size growth control.

## Budget Source
- Config file: `tools/perf_budgets.json`
- Checker: `tools/performance_budget_check.py`
- Reports:
  - `build/reports/perf/performance_budget_report.md`
  - `build/reports/perf/performance_budget_report.json`

## Current Budgets
- APK size (`max_apk_bytes`): `78,643,200` bytes (`75.00 MiB`)
- Total DEX size (`max_total_dex_bytes`): `73,400,320` bytes (`70.00 MiB`)
- Total native `.so` size (`max_native_so_bytes`): `54,525,952` bytes (`52.00 MiB`)

## Local Validation
Build release APK:

```bash
./gradlew :app:assembleRelease
```

Run budget check:

```bash
python tools/performance_budget_check.py --apk app/build/outputs/apk/release/app-release.apk --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json
```

Gate policy:
- Any metric above its budget fails CI.
- Reports are always published as artifacts for diagnosis.

## Budget Change Policy
To change a budget:
- Include before/after numbers in PR.
- Explain root cause (feature growth, dependency, ABI, assets, etc.).
- Provide mitigation plan if increase is accepted.
- Obtain approval from mobile owner.
