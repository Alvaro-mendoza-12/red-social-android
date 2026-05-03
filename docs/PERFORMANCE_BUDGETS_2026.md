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
- APK size (`max_apk_bytes`): `62,914,560` bytes (`60.00 MiB`)
- AAB size (`max_bundle_bytes`): `83,886,080` bytes (`80.00 MiB`)
- Total DEX size (`max_total_dex_bytes`): `26,214,400` bytes (`25.00 MiB`)
- Largest native `.so` ABI slice (`max_native_so_bytes`): `52,428,800` bytes (`50.00 MiB`)

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

## Runtime Telemetry
Static artifact budgets are only one layer. Runtime lag hunting now relies on these telemetry metrics:

- `feed_load`
- `direct_chat_open`
- `group_chat_open`
- `community_chat_open`
- `call_connect`
- `main_thread_stall`
- `memory_pressure_event`

`AppMetrics` emits a warning alert automatically when a recorded latency reaches `4,000 ms` or more.

## Budget Change Policy
To change a budget:
- Include before/after numbers in PR.
- Explain root cause (feature growth, dependency, ABI, assets, etc.).
- Provide mitigation plan if increase is accepted.
- Obtain approval from mobile owner.
