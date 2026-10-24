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

# Install a catalogue app through the store UI (search → result → detail → Install → stub prompt). $1 = app id.
store_install() {
  local id=$1 name stub=dev.hatch.app.${1//-/_}
  name=$(python3 -c 'import json,sys; print([a for a in json.load(open(sys.argv[1]))["apps"] if a["id"]==sys.argv[2]][0]["name"])' "$ROOT/catalogue/catalogue.json" "$id")
  adb shell am start -W -n dev.hatch/.ui.MainActivity >/dev/null; sleep 3
  $UI tap 'Get apps' dev.hatch || true; sleep 1
  $UI tap 'Search apps' dev.hatch || $UI tap 'Search' dev.hatch || return 1
  adb shell input text "$id"; sleep 2; adb shell input keyevent 4 || true; sleep 1
  for t in 1 2 3 4; do $UI has '^Install$' dev.hatch && break; $UI tap "^$name\$" dev.hatch || true; sleep 2; done
  $UI tap '^Install$' dev.hatch || return 1
  for i in $(seq 1 240); do
    adb shell pm list packages | grep -q "package:$stub$" && return 0
    $UI has 'Install failed' dev.hatch && return 1
    if $UI has 'Do you want to install|install this app'; then $UI tap '^Install$' 'packageinstaller' || true; fi
    sleep 5
  done
  return 1
}
# Frame of any window by exact title, e.g. "Hatch bubble".
win_frame() {
  adb shell dumpsys window windows | python3 -c '
import re,sys
t=sys.stdin.read(); m=re.search(r"Window\{[0-9a-f]+ u\d+ "+re.escape(sys.argv[1])+r"\}", t)
if not m: sys.exit(1)
f=re.search(r"frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", t[m.end():m.end()+6000])
if not f: sys.exit(1)
print(*f.groups())' "$1"
}
# Centre of a logged tap target (FloatingWindow.logTargets), e.g. tap_target "control Minimise" Mousepad
tap_target() {
  local xy; xy=$(adb logcat -d -s HatchFloat | grep -F "$1 at" | grep -F "for '$2'" | tail -1 | sed -E 's/.* at ([0-9]+) ([0-9]+) for.*/\1 \2/')
  [ -n "$xy" ] || return 1; adb shell input tap $xy
}
# Frame of a floating overlay window ("Hatch: <label>") as "l t r b", from WindowManager.
float_frame() {
  adb shell dumpsys window windows | python3 -c '
import re,sys
t=sys.stdin.read(); lab=sys.argv[1]
i=t.find("Hatch: "+lab)
if i<0: sys.exit(1)
m=re.search(r"frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", t[i:i+6000])
if not m: sys.exit(1)
print(*m.groups())' "$1"
}

# Run a shell snippet inside the app's guest (debug APK → run-as), mirroring LinuxRuntime's proot argv/env.
guest() {
  local lib; lib=$(adb shell pm dump dev.hatch | grep -m1 'legacyNativeLibraryDir=' | sed 's|.*legacyNativeLibraryDir=||' | tr -d '\r')/x86_64
  printf '%s\n' "$1" | adb shell "run-as dev.hatch sh -c 'cat > files/linux/tmp/hatch-probe.sh'"
  adb shell "run-as dev.hatch sh -c 'cd /data/data/dev.hatch/files && LD_LIBRARY_PATH=$lib PROOT_LOADER=$lib/libproot_loader.so PROOT_TMP_DIR=/data/data/dev.hatch/files/tmp PROOT_L2S_DIR=/data/data/dev.hatch/files/linux/.l2s $lib/libproot.so -0 -l -r /data/data/dev.hatch/files/linux -b /dev -b /proc -b /sys -b /data/data/dev.hatch/files/linux/.l2s -w /root /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME=/root /bin/sh /tmp/hatch-probe.sh'" 2>&1
}
guest_probe() {
  stamp "guest probe:"
  adb shell "run-as dev.hatch cat files/linux/etc/resolv.conf files/linux/etc/hosts" 2>&1 | sed 's/^/  /'
  guest 'getent hosts deb.debian.org; getent hosts google.com || echo GUEST-DNS-FAIL; getent ahostsv4 example.org | head -2' | sed 's/^/  /' | head -10 | tee "$OUT/guest-dns.txt"
  grep -q GUEST-DNS-FAIL "$OUT/guest-dns.txt" && stamp "WARN: guest DNS (runtime name lookups by apps) not working" || stamp "guest DNS OK"
}

adb wait-for-device
for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
adb shell settings put global package_verifier_enable 0 || true
adb shell settings put global verifier_verify_adb_installs 0 || true
adb shell input keyevent 82 || true
adb install -r -g "$APK" || fail "adb install"
adb shell appops set dev.hatch REQUEST_INSTALL_PACKAGES allow
adb shell appops set dev.hatch SYSTEM_ALERT_WINDOW allow   # "Display over other apps" (floating windows)
adb shell pm grant dev.hatch android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c

stamp "launch Hatch"
adb shell am start -W -n dev.hatch/.ui.MainActivity >/dev/null
$UI wait 'Download & set up' 60 || fail "no setup screen"
shot 01-first-run
$UI tap 'Download & set up' || fail "tap setup"
sleep 20; shot 02-setup-progress
# First run: download rootfs + apt; log what the setup screen says every minute so a slow/failed step is visible.
ok=0; retried=0
for i in $(seq 1 25); do
  if $UI wait 'Search apps' 60 >/dev/null 2>&1; then ok=1; break; fi
  screen=$($UI dump 2>/dev/null | grep dev.hatch | cut -d: -f2- | tr '\n' '|')
  stamp "setup still running ($i min): $(echo "$screen" | cut -c1-300)"
  [ $((i % 5)) -eq 0 ] && shot "02-setup-${i}min"
  if echo "$screen" | grep -q "'Failed: "; then
    shot "02-setup-failed-$retried"
    stamp "setup reported: $(echo "$screen" | grep -o "'Failed: [^']*'")"
    # A user would tap Retry: do that once (proves the retry path), then give up.
    [ $retried = 0 ] || break
    retried=1; $UI tap '^Retry$' || break
  fi
done
adb logcat -d -s HatchSetup HatchGuest HatchRootfs > "$OUT/setup-logcat.txt" 2>&1 || true
stamp "setup log: $(grep -c . "$OUT/setup-logcat.txt") lines; $(grep -cE 'Temporary failure|Could not resolve' "$OUT/setup-logcat.txt") DNS errors"
guest_probe
[ $ok = 1 ] || fail "setup did not reach store"
shot 03-store

stamp "search + install $APP"
$UI tap 'Search apps' && adb shell input text "$APP" && sleep 2
adb shell input keyevent 111 || true   # hide IME
shot 04-search
APPNAME=$(python3 -c 'import json,sys; print([a for a in json.load(open(sys.argv[1]))["apps"] if a["id"]==sys.argv[2]][0]["name"])' "$ROOT/catalogue/catalogue.json" "$APP")
adb shell input keyevent 4 || true; sleep 1   # make sure the soft keyboard is gone before tapping the result
$UI tap "^$APPNAME\$" dev.hatch || fail "no search result for $APPNAME"
sleep 2
# The first tap can be swallowed while the IME animates away; tap again until the detail page's Install button shows.
for t in 1 2 3; do $UI has '^Install$' dev.hatch && break; $UI tap "^$APPNAME\$" dev.hatch || true; sleep 2; done
shot 04b-detail
$UI tap '^Install$' dev.hatch || fail "no Install button"
sleep 6; shot 04c-installing
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
SZ=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); SX=${SZ%x*}; SY=${SZ#*x}
adb shell input swipe $((SX / 2)) $((SY * 85 / 100)) $((SX / 2)) $((SY / 6)) 300; sleep 3
shot 07-app-drawer
$UI has "$(python3 - "$ROOT/catalogue/catalogue.json" "$APP" <<'PY'
import json,sys; print([a for a in json.load(open(sys.argv[1]))["apps"] if a["id"]==sys.argv[2]][0]["name"])
PY
)" && stamp "stub icon visible in drawer" || stamp "WARN: label not found in drawer dump"
adb shell input keyevent 3

stamp "launch stub → program window"
adb logcat -c
adb shell monkey -p "$STUB" -c android.intent.category.LAUNCHER 1 >/dev/null
for i in $(seq 1 120); do adb logcat -d | grep -qE 'windowCreated|window created' && break; sleep 2; done
adb logcat -d | grep -qE 'windowCreated|window created' || fail "no Wayland window created"
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

stamp "floating window checks"
adb shell input keyevent 3; sleep 3   # home: the overlay must stay above the launcher
F=$(float_frame Mousepad) || fail "no floating overlay window 'Hatch: Mousepad' (dumpsys window)"
stamp "overlay frame: $F"
adb shell dumpsys activity recents | grep -q "dev.hatch.app.mousepad\|WindowActivity" && stamp "WARN: program left a Recents task" || stamp "no per-program Recents card (single Hatch card)"
shot 08b-floating-over-home
read L T R B <<<"$F"; SH=$(( (B - T) / 60 ))   # shadow ~10dp, handle 26dp; approx via density
D=$(adb shell wm density | grep -o '[0-9]*' | tail -1); dp() { echo $(( $1 * D / 160 )); }
HX=$(( (L + R) / 2 )); HY=$(( T + $(dp 10) + $(dp 13) ))
adb shell input swipe $HX $HY $((HX - 60)) $((HY + 300)) 600; sleep 2
F2=$(float_frame Mousepad); stamp "after drag-move: $F2"; read L2 T2 R2 B2 <<<"$F2"
[ $((T2 - T)) -gt 150 ] || fail "drag on the handle did not move the window ($F → $F2)"
CX=$(( R2 - $(dp 10) - $(dp 8) )); CY=$(( B2 - $(dp 10) - $(dp 8) ))
adb shell input swipe $CX $CY $((CX - 200)) $((CY - 250)) 700; sleep 3
F3=$(float_frame Mousepad); stamp "after corner resize: $F3"; read L3 T3 R3 B3 <<<"$F3"
[ $(( (R2 - L2) - (R3 - L3) )) -gt 100 ] && [ $(( (B2 - T2) - (B3 - T3) )) -gt 100 ] || fail "corner drag did not resize ($F2 → $F3)"
shot 08c-floating-moved-resized
adb logcat -d -s HatchWindow | grep 'bound target' | tail -2

stamp "input: tap + type"
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); WX=${W%x*}; WY=${W#*x}
adb shell input tap $(( (L3 + R3) / 2 )) $(( (T3 + B3) / 2 )); sleep 1
shot 08d-before-typing
adb shell input text "Hatch%sE2E%styping"; sleep 3
shot 09-after-typing
python3 - "$OUT/08d-before-typing.png" "$OUT/09-after-typing.png" <<'PY' || fail "typing changed nothing"
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
adb logcat -d | grep -qE 'windowDestroyed|window destroyed' && stamp "WARN: window destroyed during resize"
float_frame Mousepad >/dev/null || fail "floating window gone after fold-resize"

stamp "open the store while the program keeps running"
adb shell am start -W -n dev.hatch/.ui.MainActivity >/dev/null; sleep 4
float_frame Mousepad >/dev/null || fail "floating window closed when the store opened"
sleep 3; float_frame Mousepad >/dev/null || fail "program exited after the store opened"
shot 10b-store-with-floating-app

stamp "floating over another Android app (Settings)"
adb shell am start -W -a android.settings.SETTINGS >/dev/null; sleep 4
float_frame Mousepad >/dev/null || fail "floating window hidden by another app"
shot 10c-floating-over-settings-app

stamp "minimise to the edge bubble, restore from it"
F=$(float_frame Mousepad); read L T R B <<<"$F"
adb logcat -c
adb shell input tap $(( (L + R) / 2 )) $(( T + $(dp 10) + $(dp 13) )); sleep 2   # tap the pill handle → window controls
shot 10d-window-controls
tap_target "control Minimise" Mousepad || fail "window controls did not appear on handle tap"
sleep 2
float_frame Mousepad >/dev/null && fail "window still on screen after Minimise"
BF=$(win_frame "Hatch bubble") || fail "no edge bubble after minimising"
stamp "bubble frame: $BF"; read BL BT BR BB <<<"$BF"
shot 10e-minimised-to-bubble
adb logcat -c
adb shell input tap $(( (BL + BR) / 2 )) $(( (BT + BB) / 2 )); sleep 2
shot 10f-bubble-menu
tap_target "bubble-menu ▢  Mousepad" bubble || fail "bubble menu did not list the minimised window"
sleep 2
float_frame Mousepad >/dev/null || fail "window not restored from the bubble"
shot 10g-restored-from-bubble
AS=$(adb shell pidof dev.hatch | tr -d '\r'); [ "$AS" = "$PID_AFTER" ] || fail "process restarted during minimise/restore"

stamp "bubble → Open Hatch (store) while the window floats"
adb shell input keyevent 3; sleep 2
adb logcat -c
adb shell input tap $(( (BL + BR) / 2 )) $(( (BT + BB) / 2 )); sleep 2
tap_target "bubble-menu Open Hatch" bubble || fail "bubble menu has no Open Hatch"
sleep 4
$UI has 'Search apps|Get apps' dev.hatch || fail "Open Hatch from the bubble did not show the store"
float_frame Mousepad >/dev/null || fail "window closed when the store opened from the bubble"
shot 10h-store-opened-from-bubble
cp "$OUT/08b-floating-over-home.png" "$OUT/P1-floating-over-home.png"

stamp "P2: second app (galculator) installed from the store; two windows floating over another Android app"
# Park Mousepad in the bubble so the overlay doesn't cover the store while installing, then restore it.
read L T R B <<<"$(float_frame Mousepad)"; adb logcat -c
adb shell input tap $(( (L + R) / 2 )) $(( T + $(dp 10) + $(dp 13) )); sleep 2
tap_target "control Minimise" Mousepad || fail "no Minimise control before second install"; sleep 2
store_install galculator || { shot P2-install-failed; fail "galculator install from the store failed"; }
read BL BT BR BB <<<"$(win_frame 'Hatch bubble')" || fail "bubble missing"
adb logcat -c; adb shell input tap $(( (BL + BR) / 2 )) $(( (BT + BB) / 2 )); sleep 2
tap_target "bubble-menu ▢  Mousepad" bubble || fail "bubble menu has no Mousepad"; sleep 2
float_frame Mousepad >/dev/null || fail "Mousepad not restored from bubble"
adb shell am start -W -a android.settings.SETTINGS >/dev/null; sleep 3
adb logcat -c
adb shell monkey -p dev.hatch.app.galculator -c android.intent.category.LAUNCHER 1 >/dev/null
for i in $(seq 1 60); do float_frame Calculator >/dev/null && break; sleep 2; done
float_frame Calculator >/dev/null || fail "Calculator (galculator) did not open as a floating window"
sleep 6
GF=$(float_frame Calculator); MF=$(float_frame Mousepad) || fail "mousepad window gone when galculator opened"
stamp "two floating windows: Mousepad [$MF] Galculator [$GF]"
[ "$GF" != "$MF" ] || fail "windows not cascaded"
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity' | grep -q settings && stamp "Settings is the resumed app underneath" || stamp "WARN: Settings not on top underneath"
shot P2-two-windows-over-settings

stamp "P3: store opened while both windows float"
adb shell am start -W -n dev.hatch/.ui.MainActivity >/dev/null; sleep 4
float_frame Mousepad >/dev/null && float_frame Calculator >/dev/null || fail "a window closed when the store opened"
shot P3-store-with-two-floating-windows

stamp "P4: maximise then restore (Mousepad)"
adb shell input keyevent 3; sleep 2
F=$(float_frame Mousepad); read L T R B <<<"$F"
adb logcat -c
adb shell input tap $(( (L + R) / 2 )) $(( T + $(dp 10) + $(dp 13) )); sleep 2
tap_target "control Maximise" Mousepad || fail "no Maximise control"
sleep 3; MX=$(float_frame Mousepad); read ML MT MR MB <<<"$MX"
stamp "maximised frame: $MX (screen ${WX}x${WY})"
[ $(( MR - ML )) -ge $(( WX * 95 / 100 )) ] || fail "maximise did not fill the width ($F → $MX)"
shot P4a-maximised
adb logcat -c
adb shell input tap $(( (ML + MR) / 2 )) $(( (MT > 0 ? MT : 0) + $(dp 10) + $(dp 13) )); sleep 2
tap_target "control Maximise" Mousepad || fail "no Maximise control on the maximised window"
sleep 3; RF=$(float_frame Mousepad); stamp "restored frame: $RF (was $F)"
[ "$RF" = "$F" ] || fail "restore did not return to the floating bounds ($F → $RF)"
shot P4b-restored
adb logcat -d -s HatchRuntime HatchWindow HatchSession > "$OUT/logcat.txt" 2>&1
stamp "mousepad leg OK"

if [ "${E2E_WINE:-1}" = 1 ]; then
  stamp "Windows app via Wine: Notepad++ (x64 build on this x86_64 emulator; ARM64 build is proven on the arm64 runner)"
  WSTUB=dev.hatch.app.notepadpp
  adb shell am start -S -W -n dev.hatch/.ui.MainActivity >/dev/null
  $UI wait 'Search apps' 60 || fail "store not shown again"
  $UI tap 'Search apps' && adb shell input text "notepad" && sleep 2
  adb shell input keyevent 111 || true
  shot 11-search-notepadpp
  $UI tap '^Notepad\+\+$' dev.hatch || fail "no search result for Notepad++"
  sleep 2; shot 11b-notepadpp-detail
  $UI tap '^Install$' dev.hatch || fail "no Install button for Notepad++"
  for i in $(seq 1 360); do
    adb shell pm list packages | grep -q "package:$WSTUB$" && break
    if $UI has 'Install failed' dev.hatch; then
      $UI tap '^Details$' dev.hatch || true; sleep 2; shot 12-wine-install-failed
      adb logcat -d -s HatchInstall > "$OUT/wine-install-logcat.txt" 2>&1; fail "Notepad++ install failed"
    fi
    if $UI has 'Do you want to install|install this app'; then shot 12-wine-stub-prompt; $UI tap '^Install$' 'packageinstaller' || true; fi
    [ $((i % 12)) -eq 0 ] && stamp "wine install running ($((i / 12)) min): $($UI dump 2>/dev/null | grep -m1 -E 'Notepad\+\+ —' | cut -c1-120)"
    sleep 5
  done
  adb shell pm list packages | grep -q "package:$WSTUB$" || fail "stub $WSTUB not installed"
  stamp "Notepad++ stub installed"
  adb logcat -c
  adb shell monkey -p "$WSTUB" -c android.intent.category.LAUNCHER 1 >/dev/null
  for i in $(seq 1 240); do adb logcat -d -s HatchWindow | grep -q "bound target.*Notepad++" && break; sleep 3; done
  adb logcat -d -s HatchRuntime HatchWindow HatchSession > "$OUT/wine-logcat.txt" 2>&1
  grep -q "bound target.*Notepad++" "$OUT/wine-logcat.txt" || fail "no Notepad++ window (see wine-logcat.txt)"
  sleep 10; shot 13-notepadpp-window
  python3 - "$OUT/13-notepadpp-window.png" <<'PY' || fail "Notepad++ window looks empty"
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB"); w, h = im.size
px = list(im.crop((0, h // 8, w, h * 7 // 8)).getdata())[::97]
u = len(set(px)); print(f"notepad++ uniq colours={u}"); sys.exit(0 if u > 12 else 1)
PY
  NF=$(float_frame Notepad++) && read NL NT NR NB <<<"$NF" || { NL=0; NT=0; NR=$WX; NB=$WY; }
  stamp "Notepad++ floating frame: ${NF:-none (task mode)}"
  adb shell input tap $(( (NL + NR) / 2 )) $(( (NT + NB) / 2 )); sleep 1; shot 13b-notepadpp-focused
  adb shell input text "Wine%son%sHatch"; sleep 3; shot 14-notepadpp-typed
  python3 - "$OUT/13b-notepadpp-focused.png" "$OUT/14-notepadpp-typed.png" <<'PY' || fail "typing into Notepad++ changed nothing"
import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert("RGB"); b = Image.open(sys.argv[2]).convert("RGB")
bbox = ImageChops.difference(a, b).getbbox(); print("diff bbox", bbox); sys.exit(0 if bbox else 1)
PY
  stamp "wine leg OK"
fi
stamp "emu-e2e OK"
