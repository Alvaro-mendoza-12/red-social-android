# Big Tech Readiness Plan (2026)

## 1) North Star
Convert the app into a production-grade mobile platform with deterministic quality gates, measurable reliability, and safe delivery discipline.

## 2) Target KPIs (Release Blocking)
- Crash-free users (7d): `>= 99.8%`
- ANR rate (7d): `<= 0.30%`
- Message delivery success (direct chat, 5m): `>= 99.9%`
- Unread convergence correctness (chat enter/exit): `100%` in regression suite
- CI quality gate pass rate (main branch): `>= 95%`
- P95 cold start (mid-range Android): `<= 2.2s`

## 3) Execution Tracks

### Track A: Quality Gates and CI/CD
Scope:
- Enforce automated checks before merge and on release branches.
- Persist artifacts for diagnosis.

Deliverables:
- [x] `:app:qualityGateDebug` task (`testDebugUnitTest` + `lintDebug`).
- [x] GitHub Actions workflow upgraded to:
  - quality gate job
  - release smoke build (`assembleRelease`)
  - report artifact upload
- [x] Local scripts for reproducible gate execution:
  - `tools/quality_gate.ps1`
  - `tools/quality_gate.sh`

Acceptance criteria:
- Every PR triggers quality gate.
- Failed lint/tests block promotion.
- Release smoke build fails fast on packaging/proguard regressions.

### Track B: Messaging Reliability (1:1, Group, Community)
Scope:
- Remove unread counter drift and state races.
- Guarantee deterministic behavior on screen open/close.

Deliverables:
- [x] 1:1 chat unread convergence hardening:
  - Force read sync on chat visibility enter.
  - Avoid unread increment while active chat is visible.
  - Include `sent` in read transition path.
  - Ensure active chat session updates after placeholder->real chat transitions.
- [x] Add regression tests for unread lifecycle:
  - enter chat with pending unread
  - leave and re-enter
  - incoming messages while screen visible/hidden
- [x] Apply same deterministic tests to group/community paths.

Acceptance criteria:
- Counter always drops to zero after entering chat and loading visible messages.
- No false unread increments while the same chat is open.

### Track C: Crash, ANR, and Runtime Hardening
Scope:
- Turn runtime failures into observable signals.
- Reduce risky long-lived loops and noisy background behaviors.

Deliverables:
- [x] Capture and store structured crash payloads (thread, module, feature, user/session IDs).
- [x] Add watchdog alerts for repeated reconnect loops and memory-pressure events.
- [x] Audit realtime loops for lifecycle-safe cancellation and bounded retries.
- [x] Add production-safe logging strategy (sampled, leveled, no heartbeat spam).

Acceptance criteria:
- Every crash has actionable context.
- Reconnect loops are bounded and measurable.
- No perpetual high-frequency logs in production.

### Track D: Test Strategy (Pyramid)
Scope:
- Build confidence with layered tests.

Deliverables:
- [ ] Unit:
  - repository merge/sync conflict tests
  - unread/read receipt transition tests
- [x] Unit:
  - observability alert thresholds for realtime reconnect + memory pressure
- [ ] Integration:
  - Room + repository synchronization tests
  - realtime event ordering tests
- [x] UI/instrumented:
  - navigation + chat lifecycle smoke tests
  - offline/online transition tests

Acceptance criteria:
- Critical flows covered by deterministic tests.
- Regressions detected before merge.

### Track E: Security and Compliance
Scope:
- Enforce backend and client hardening.

Deliverables:
- [x] RLS and RPC permission audit checklist for all messaging/media functions.
- [ ] Rate limit + abuse signatures audit and monitoring dashboard.
- [x] SQL static security audit + CI gating with baseline and report artifacts.
- [ ] Secrets hygiene (rotation policy, CI secret usage audit).

Acceptance criteria:
- No unauthenticated access path to protected operations.
- Abuse patterns are detected and rate-limited.

### Track F: Performance and Resource Management
Scope:
- Keep memory, battery, and network under strict budgets.

Deliverables:
- [x] Release artifact performance budget automation (APK/DEX/native checks).
- [ ] Baseline profiles for startup and top 3 screens.
- [ ] Memory profiling sessions (chat, media, voice rooms).
- [ ] Network budget dashboard for polling/realtime workloads.

Acceptance criteria:
- P95 startup and memory targets met on representative devices.

## 4) Milestones

### Milestone 0 (Implemented now)
- [x] CI quality gate + release smoke
- [x] Local quality gate scripts
- [x] 1:1 unread convergence fixes applied

### Milestone 1 (Next)
- [x] Add unread lifecycle automated tests (DM/Group/Community)
- [x] Realtime loop hardening pass (DM full loop, Group/Community debug heartbeat gating)
- [x] Crash context enrichment

### Milestone 2
- [x] Instrumented smoke pipeline for app launch/navigation baseline
- [x] Realtime reconnect observability metrics (DM/Group/Community message loops)
- [x] Performance budget automation for release artifacts
- [ ] Profiling automation and baseline profile rollout
- [ ] Security audit completion and sign-off

## 5) PR/Release Policy
- PR merge allowed only if:
  - quality gate passes
  - no critical lint/test failures
  - reviewer confirms no lifecycle regressions
- Release promotion allowed only if:
  - release smoke build passes
  - crash-free + ANR KPIs within target window

## 6) Operational Commands
- Local quality gate (PowerShell):
  - `.\tools\quality_gate.ps1`
- Local quality gate (bash):
  - `./tools/quality_gate.sh`
- SQL security audit:
  - `python tools/security_sql_audit.py --root . --baseline tools/security_audit_baseline.json --report build/reports/security/sql_security_audit.md --json build/reports/security/sql_security_audit.json --fail-threshold HIGH`
- Performance budget check:
  - `python tools/performance_budget_check.py --apk app/build/outputs/apk/release/app-release.apk --budgets tools/perf_budgets.json --report build/reports/perf/performance_budget_report.md --json build/reports/perf/performance_budget_report.json`
- CI gate task:
  - `:app:qualityGateDebug`
