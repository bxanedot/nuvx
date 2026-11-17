#!/usr/bin/env bash
# Installs the phone APK on a running emulator, opens it, and fails if it crashes or exits.
# Usage: scripts/android-launch-smoke.sh path/to/app.apk [package]
set -euo pipefail
apk="$1"
pkg="${2:-com.licht.nuvx}"

adb install -r -g "$apk"
adb logcat -c
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null

# Cold start on a CI emulator can take a while; give it the same 30 s a slow phone gets.
for _ in $(seq 1 30); do
  sleep 1
  if adb shell pidof "$pkg" >/dev/null; then break; fi
done
sleep 15 # stay open long enough for start-up work (database, player service) to run

mkdir -p build/smoke
adb logcat -d > build/smoke/logcat.txt
adb exec-out screencap -p > build/smoke/launch.png || true

if grep -q "FATAL EXCEPTION" build/smoke/logcat.txt && grep -A3 "FATAL EXCEPTION" build/smoke/logcat.txt | grep -q "$pkg"; then
  grep -A30 "FATAL EXCEPTION" build/smoke/logcat.txt
  echo "::error::$pkg crashed on launch"
  exit 1
fi
if ! adb shell pidof "$pkg" >/dev/null; then
  echo "::error::$pkg is not running 15 s after launch"
  exit 1
fi
focus="$(adb shell dumpsys window | grep -m1 mCurrentFocus || true)"
echo "Focused window: $focus"
echo "$focus" | grep -q "$pkg" || { echo "::error::$pkg is running but not in the foreground"; exit 1; }
echo "Launch smoke test passed"
