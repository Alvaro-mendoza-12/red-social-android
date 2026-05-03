# Performance Profile - 2026-04-19

## What Was Applied

- End-to-end harness added for `logout -> login with saved account -> home navigation`.
- Runtime auth/session overrides added for instrumentation tests so saved-account flows can run without depending on live Supabase hydration.
- New latency telemetry added for:
  - `direct_chat_open`
  - `group_chat_open`
  - `community_chat_open`
  - `call_connect`
- Existing feed telemetry kept as the baseline for `feed_load`.
- Global runtime lag and memory pressure watchers remain active through:
  - `main_thread_stall`
  - `memory_pressure_event`

## Verification Performed

- `./gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --no-daemon --console=plain`
- `node --check signaling-server/index.js`

Result:

- Android app code compiled.
- Android instrumentation test sources compiled.
- Signaling server passed syntax validation.

## End-to-End Test Status

Implemented test:

- `app/src/androidTest/java/conectate/com/ui/SavedAccountLogoutLoginFlowTest.kt`

What it validates:

- Boot with saved account fixture
- Navigate to profile
- Open settings
- Logout
- Return to login and show saved accounts
- Re-enter with saved account
- Return to home/messages

Limitation:

- This environment did not have `adb` available, so the test could not be executed on a real emulator/device from this session.

## Runtime Metrics To Watch

These metrics now represent the most important user-perceived latency paths:

- `feed_load`: initial feed hydration
- `direct_chat_open`: DM open until first usable message state
- `group_chat_open`: group open until first usable state
- `community_chat_open`: community open until first usable state
- `call_connect`: call setup until connected media path or failure

Alert behavior:

- `AppMetrics` emits a warning when latency reaches `>= 4000 ms`
- repeated failures escalate to warning/critical alerts

## Highest Risk Performance Areas

- Feed hydration remains sensitive to network and media density.
- Chat open paths can degrade if profile/session resolution stalls before messages subscribe.
- Calls remain the most fragile flow because socket, auth token, WebRTC and media state all have to converge.
- Live presence checks depend on the signaling HTTP endpoint and can surface slow-network jitter if the server is distant or congested.

## Next Capture Steps

Run on a connected device/emulator:

```bash
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=conectate.com.ui.SavedAccountLogoutLoginFlowTest
```

Then watch:

- telemetry events for the metrics above
- `main_thread_stall`
- reconnect/failure alerts around calls and realtime

## Outcome

This pass improves your ability to catch real lag instead of guessing:

- the critical saved-account auth flow is now testable
- chat/call latency paths are instrumented
- runtime alerts already flag slow openings and stability pressure
