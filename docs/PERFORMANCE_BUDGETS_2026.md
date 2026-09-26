# Performance Budgets (2026)

## Scope
Release artifact and Google Play device-delivery budgets for Android size growth control.

## Budget Source
- Config file: `tools/perf_budgets.json`
- Checker: `tools/performance_budget_check.py`
- Device-size engine: repository-pinned `tools/bundletool/bundletool-all.jar`
- Reports:
  - `build/reports/perf/performance_budget_report.md`
  - `build/reports/perf/performance_budget_report.json`

## Current Budgets
- APK size (`max_apk_bytes`): `104,857,600` bytes (`100.00 MiB`), only for legacy/direct APK delivery.
- AAB upload artifact (`max_bundle_bytes`): `209,715,200` bytes (`200.00 MiB`). This controls repository growth; it is not a device-download metric.
- Maximum compressed download calculated by bundletool (`max_device_download_bytes`): `104,857,600` bytes (`100.00 MiB`).
- Compressed DEX payload (`max_total_dex_bytes`): `26,214,400` bytes (`25.00 MiB`).
- Largest compressed native `.so` ABI slice (`max_native_so_bytes`): `31,457,280` bytes (`30.00 MiB`).

## Local Validation
Build the signed release bundle (requires the original upload key):

```bash
./gradlew :app:bundleRelease
```

Run budget check:

```bash
python tools/verify_android_release.py app/build/outputs/bundle/release/app-release.aab --expected-cert-sha256 "$NOVVIN_UPLOAD_CERT_SHA256"
python tools/verify_android_page_size.py app/build/outputs/bundle/release/app-release.aab
python tools/performance_budget_check.py --artifacts app/build/outputs/bundle/release/app-release.aab --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json
```

Gate policy:
- Any metric above its budget fails CI.
- The release gate also requires `PAGE_ALIGNMENT_16K` and 16 KiB ELF load-segment alignment for every 64-bit native library.
- AAB device size is obtained from `bundletool get-size total` across SDK, ABI and screen-density dimensions.
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
