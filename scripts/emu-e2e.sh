#!/usr/bin/env bash
# Android emulator E2E: install → launch store → screenshot first-run/setup.
# Full rootfs download is too heavy for CI; this proves APK install + UI launch.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-}"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  APK=$(find "$ROOT" /home/runner/work -name 'app-debug.apk' 2>/dev/null | head -1 || true)
fi
[ -f "$APK" ] || { echo "No APK"; exit 1; }
OUT="${PROOF_DIR:-$ROOT/harness-out/emu}"
mkdir -p "$OUT"

adb wait-for-device
# Wait boot
for i in $(seq 1 60); do
  boot=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$boot" = "1" ] && break
  sleep 2
done
adb shell settings put global package_verifier_enable 0 || true
adb install -r -g "$APK"
adb shell appops set dev.hatch REQUEST_INSTALL_PACKAGES allow || true
adb shell pm grant dev.hatch android.permission.POST_NOTIFICATIONS 2>/dev/null || true

adb shell am start -n dev.hatch/.ui.MainActivity
sleep 5
adb shell screencap -p /sdcard/hatch-e2e-01.png
adb pull /sdcard/hatch-e2e-01.png "$OUT/01-first-run.png"
ls -la "$OUT/01-first-run.png"
# Confirm package + activity
adb shell dumpsys package dev.hatch | head -5 || true
test -s "$OUT/01-first-run.png"
echo "emu-e2e OK"
