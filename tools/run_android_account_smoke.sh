#!/usr/bin/env bash
# Fresh hosted runner + fresh AVD + one instrumentation process per account case.
set -Eeuo pipefail

case "${1:-}" in
  SavedAccountLogoutLoginFlowTest|SecondarySavedAccountLoginFlowTest|MessagesMultiAccountSwitchingTest) test_class="conectate.com.ui.$1" ;;
  *) echo 'Expected one of the three saved-account smoke classes.' >&2; exit 2 ;;
esac
[[ "${GITHUB_ACTIONS:-}" == true && "${GITHUB_REF:-}" == refs/heads/correcciones-dot && "${NOVVIN_CI_FIXTURE:-}" == true ]] || {
  echo 'This isolated runner is restricted to correcciones-dot CI fixtures.' >&2; exit 2;
}
[[ -r /dev/kvm && -w /dev/kvm ]] || { echo 'KVM access is required; software fallback is disabled.' >&2; exit 2; }

export ANDROID_SERIAL=emulator-5554
report_dir="app/build/reports/account-smoke"
mkdir -p "$report_dir"
emulator_pid=''
logcat_pid=''
cleanup() {
  status=$?
  set +e
  timeout 15s adb exec-out screencap -p > "$report_dir/final-screen.png"
  timeout 15s adb shell dumpsys activity exit-info conectate.com > "$report_dir/exit-info.txt"
  [[ -z "$logcat_pid" ]] || kill "$logcat_pid" 2>/dev/null
  timeout 15s adb emu kill
  [[ -z "$emulator_pid" ]] || kill "$emulator_pid" 2>/dev/null
  exit "$status"
}
trap cleanup EXIT

printf 'no\n' | avdmanager create avd --name saved_account_qa --package 'system-images;android-34;default;x86_64' --device pixel_6 --force
"$ANDROID_HOME/emulator/emulator" -avd saved_account_qa -port 5554 -accel on -no-window \
  -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -no-metrics -cores 2 -memory 2048 \
  > "$report_dir/emulator.log" 2>&1 &
emulator_pid=$!
timeout 180s adb wait-for-device
deadline=$((SECONDS + 300))
while [[ "$(timeout 10s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]]; do
  kill -0 "$emulator_pid"
  (( SECONDS < deadline )) || { echo 'Emulator did not complete boot.' >&2; exit 1; }
  sleep 2
done

# Disable emulator networking before Gradle installs or launches the application.
# This changes only this disposable AVD, not host network/security permissions.
adb shell svc wifi disable
adb shell svc data disable
adb shell cmd connectivity airplane-mode enable
[[ "$(adb shell settings get global airplane_mode_on | tr -d '\r')" == 1 ]]
[[ "$(adb shell settings get global wifi_on | tr -d '\r')" == 0 ]]
[[ "$(adb shell settings get global mobile_data | tr -d '\r')" == 0 ]]
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent 82
adb logcat -v threadtime > "$report_dir/logcat.log" 2>&1 &
logcat_pid=$!

./gradlew --no-daemon --max-workers=2 --stacktrace :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$test_class"
python tools/verify_account_smoke_result.py "$test_class"
