#!/usr/bin/env bash
# Android emulator E2E through the real UI:
#   first run → "Download & set up" (Debian 13 under proot) → store → search → Install mousepad
#   → system installs the generated launcher stub (own icon) → tap stub → real program window → input.
# x86_64 KVM emulator: uses the x86_64 Termux proot + amd64 Debian; arm64-only bits are proven on the arm64 runner.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$(find "$ROOT/apks" "$ROOT/app/build/outputs" -name app-debug.apk 2>/dev/null | head -1)}"; [ -f "$APK" ] || { echo "no apk"; exit 1; }; echo "Using $APK"; APP="${E2E_APP:-mousepad}"; STUB="dev.hatch.app.${APP//-/_}"
OUT="${PROOF_DIR:-$ROOT/harness-out/emu}"; mkdir -p "$OUT"
UI="python3 $ROOT/scripts/ui.py"
t0=$(date +%s); stamp() { echo "[$(( $(date +%s) - t0 ))s] $*"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; stamp "screenshot $1 ($(stat -c%s "$OUT/$1.png") bytes)"; }
fail() { stamp "FAIL: $*"; shot zz-fail; $UI dump || true; adb logcat -d -s HatchRuntime HatchSetup HatchInstall HatchStub HatchWindow HatchSession HatchGuest HatchAudio AndroidRuntime:E > "$OUT/logcat.txt" 2>&1; tail -60 "$OUT/logcat.txt"; exit 1; }

# Run a shell snippet inside the app's guest (debug APK → run-as), mirroring LinuxRuntime's proot argv/env.
guest() {
  local lib; lib=$(adb shell pm dump dev.hatch | grep -m1 'legacyNativeLibraryDir=' | cut -d= -f2 | tr -d '\r')/x86_64
  printf '%s\n' "$1" | adb shell "run-as dev.hatch sh -c 'cat > files/linux/tmp/hatch-probe.sh'"
  adb shell "run-as dev.hatch sh -c 'cd /data/data/dev.hatch/files && LD_LIBRARY_PATH=$lib PROOT_LOADER=$lib/libproot_loader.so PROOT_TMP_DIR=/data/data/dev.hatch/files/tmp PROOT_L2S_DIR=/data/data/dev.hatch/files/linux/.l2s $lib/libproot.so -0 -l -r /data/data/dev.hatch/files/linux -b /dev -b /proc -b /sys -b /data/data/dev.hatch/files/linux/.l2s -w /root /bin/sh /tmp/hatch-probe.sh'" 2>&1
}
guest_probe() {
  stamp "guest probe:"
  adb shell "run-as dev.hatch cat files/linux/etc/resolv.conf files/linux/etc/hosts" 2>&1 | sed 's/^/  /'
  guest 'getent hosts deb.debian.org; getent hosts google.com || echo GUEST-DNS-FAIL; getent ahostsv4 example.org | head -2' | sed 's/^/  /' | head -10
}

adb wait-for-device
for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
adb shell settings put global package_verifier_enable 0 || true
adb shell settings put global verifier_verify_adb_installs 0 || true
adb shell input keyevent 82 || true
adb install -r -g "$APK" || fail "adb install"
adb shell appops set dev.hatch REQUEST_INSTALL_PACKAGES allow
adb shell pm grant dev.hatch android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c

stamp "launch Hatch"
adb shell am start -W -n dev.hatch/.ui.MainActivity >/dev/null
$UI wait 'Download & set up' 60 || fail "no setup screen"
shot 01-first-run
$UI tap 'Download & set up' || fail "tap setup"
sleep 20; shot 02-setup-progress
# First run: download rootfs + apt; log what the setup screen says every minute so a slow/failed step is visible.
ok=0
for i in $(seq 1 25); do
  if $UI wait 'Search apps' 60 >/dev/null 2>&1; then ok=1; break; fi
  stamp "setup still running ($i min): $($UI dump 2>/dev/null | grep dev.hatch | cut -d: -f2- | tr '\n' '|' | cut -c1-300)"
  [ $((i % 5)) -eq 0 ] && shot "02-setup-${i}min"
done
adb logcat -d -s HatchSetup HatchGuest > "$OUT/setup-logcat.txt" 2>&1 || true
stamp "setup log: $(grep -c . "$OUT/setup-logcat.txt") lines; $(grep -cE 'Temporary failure|Could not resolve' "$OUT/setup-logcat.txt") DNS errors"
guest_probe
[ $ok = 1 ] || fail "setup did not reach store"
shot 03-store

stamp "search + install $APP"
$UI tap 'Search apps' && adb shell input text "$APP" && sleep 2
adb shell input keyevent 111 || true   # hide IME
shot 04-search
$UI tap '^Install$' dev.hatch || fail "no Install button"
# PackageInstaller confirmation for the generated stub APK
for i in $(seq 1 300); do
  adb shell pm list packages | grep -q "package:$STUB$" && break
  if $UI has 'Install failed' dev.hatch; then
    $UI tap '^Details$' dev.hatch || true; sleep 2; shot 05-install-failed
    adb logcat -d -s HatchInstall > "$OUT/install-logcat.txt" 2>&1; guest_probe; fail "install failed in store"
  fi
  if $UI has 'Do you want to install|install this app'; then shot 05-stub-prompt; $UI tap '^Install$' 'packageinstaller' || true; fi
  sleep 5
done
adb shell pm list packages | grep -q "package:$STUB$" || fail "stub $STUB not installed"
stamp "stub installed: $STUB"
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | grep -q "$STUB" \
  || fail "stub has no launcher activity"
$UI wait '^Open$' 120 dev.hatch || fail "store did not show Open"
shot 06-store-installed

stamp "app drawer"
adb shell input keyevent 3; sleep 2
adb shell input swipe 540 1900 540 300 300; sleep 3
shot 07-app-drawer
$UI has "$(python3 - "$ROOT/catalogue/catalogue.json" "$APP" <<'PY'
import json,sys; print([a for a in json.load(open(sys.argv[1]))["apps"] if a["id"]==sys.argv[2]][0]["name"])
PY
)" && stamp "stub icon visible in drawer" || stamp "WARN: label not found in drawer dump"
adb shell input keyevent 3

stamp "launch stub → program window"
adb logcat -c
adb shell monkey -p "$STUB" -c android.intent.category.LAUNCHER 1 >/dev/null
for i in $(seq 1 120); do adb logcat -d | grep -q 'window created' && break; sleep 2; done
adb logcat -d | grep -q 'window created' || fail "no Wayland window created"
sleep 8
shot 08-app-window
adb shell dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity' | head -3
python3 - "$OUT/08-app-window.png" <<'PY' || fail "window looks empty"
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB"); w, h = im.size
crop = im.crop((0, h // 8, w, h * 7 // 8)); px = list(crop.getdata())[::97]
u = len(set(px)); print(f"app window uniq colours={u}"); sys.exit(0 if u > 12 else 1)
PY

stamp "input: tap + type"
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); WX=${W%x*}; WY=${W#*x}
adb shell input tap $((WX / 2)) $((WY / 2)); sleep 1
adb shell input text "Hatch%sE2E%styping"; sleep 3
shot 09-after-typing
python3 - "$OUT/08-app-window.png" "$OUT/09-after-typing.png" <<'PY' || fail "typing changed nothing"
import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert("RGB"); b = Image.open(sys.argv[2]).convert("RGB")
bbox = ImageChops.difference(a, b).getbbox(); print("diff bbox", bbox); sys.exit(0 if bbox else 1)
PY

stamp "fold-like resize (config change must not kill the session)"
PID_BEFORE=$(adb shell pidof dev.hatch | tr -d '\r')
adb shell wm size 1812x2176; sleep 6; shot 10-unfolded
adb shell wm size reset; sleep 4
PID_AFTER=$(adb shell pidof dev.hatch | tr -d '\r')
[ "$PID_BEFORE" = "$PID_AFTER" ] || fail "process restarted on resize ($PID_BEFORE → $PID_AFTER)"
adb logcat -d | grep -q 'window destroyed' && stamp "WARN: window destroyed during resize"
adb logcat -d -s HatchRuntime HatchWindow HatchSession > "$OUT/logcat.txt" 2>&1
stamp "emu-e2e OK"
