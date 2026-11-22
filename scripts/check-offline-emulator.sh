#!/usr/bin/env bash
# Run on the disposable mobile CI emulator, after connectedDebugAndroidTest.
set -euo pipefail
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
app=dev.mjwatch.mobile
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
test_class="$app.OfflinePlaybackUiTest"
adb install -r app-mobile/build/outputs/apk/debug/app-mobile-debug.apk
adb install -r app-mobile/build/outputs/apk/androidTest/debug/app-mobile-debug-androidTest.apk
run_phase() {
  local result
  result=$(adb shell am instrument -w -e isolatedOffline true -e offlineViewport "${OFFLINE_VIEWPORT:-phone}" -e class "$test_class#$1" "$runner")
  echo "$result"
  grep -Eq '^OK \(1 test\)' <<< "$result"
}
run_phase downloadMovieAndSeason
adb shell am force-stop "$app"
test -z "$(adb shell pidof "$app" || true)"
trap 'adb shell cmd connectivity airplane-mode disable; adb shell svc wifi enable; adb shell svc data enable' EXIT
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
run_phase playEachDownloadAfterColdStartWithoutNetwork
