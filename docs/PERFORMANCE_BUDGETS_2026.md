# Performance Budgets (2026)

## Scope
Release artifact budgets for Android APK packaging size growth control.

## Budget Source
- Config file: `tools/perf_budgets.json`
- Checker: `tools/performance_budget_check.py`
- Reports:
  - `build/reports/perf/performance_budget_report.md`
  - `build/reports/perf/performance_budget_report.json`

## Last Local Release Validation

Generated report: `2026-05-07T05:34:20Z`.

`assembleRelease --no-daemon --console=plain --max-workers=1` finished successfully on `2026-05-07` local time and regenerated four unsigned split APKs:

- `app-arm64-v8a-release-unsigned.apk`: `53,973,534` bytes (`51.47 MiB`)
- `app-armeabi-v7a-release-unsigned.apk`: `38,963,366` bytes (`37.16 MiB`)
- `app-x86-release-unsigned.apk`: `53,277,250` bytes (`50.81 MiB`)
- `app-x86_64-release-unsigned.apk`: `56,766,234` bytes (`54.14 MiB`)

All APK size, total DEX size (`12,442,508` bytes / `11.87 MiB`) and largest native `.so` ABI slice checks passed.

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
python tools/performance_budget_check.py --apk app/build/outputs/apk/release --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json
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
