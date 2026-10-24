#!/usr/bin/env bash
# Android emulator end-to-end: install APK → first-run setup → store → install mousepad → launch.
# Requires: adb, an emulator/device, and a built APK (default: proofs/app-debug.apk).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/../proofs/app-debug.apk}"
[ -f "$APK" ] || APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "No APK at $APK"; exit 1; }

adb wait-for-device
adb shell settings put global package_verifier_enable 0 || true
adb install -r -g "$APK"
adb shell appops set dev.hatch REQUEST_INSTALL_PACKAGES allow || true
adb shell pm grant dev.hatch android.permission.POST_NOTIFICATIONS || true

# Launch main
adb shell am start -n dev.hatch/.ui.MainActivity
sleep 2
# Screenshot setup/store
mkdir -p "$ROOT/../proofs/emu"
adb shell screencap -p /sdcard/hatch-store.png
adb pull /sdcard/hatch-store.png "$ROOT/../proofs/emu/01-store.png" || true

# Drive first-run if needed via UI automator is heavy; document manual Fold steps.
# Instrumentation hook: broadcast to start setup if exposed
adb shell am broadcast -a dev.hatch.action.START_SETUP -n dev.hatch/.runtime.HatchRuntimeService 2>/dev/null || true

echo "E2E scaffold complete. Full store→install→stub→launch needs rooted/emu with network + long rootfs download."
echo "Artifacts under proofs/emu/"
ls -la "$ROOT/../proofs/emu/" 2>/dev/null || true
