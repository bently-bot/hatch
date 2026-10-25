#!/usr/bin/env bash
# Android emulator E2E through the real UI:
#   first run → "Download & set up" (Debian 13 under proot) → store → search → Install mousepad
#   → launcher pins the app's Home screen icon (shortcut, no APK) → tap it → real program window → input
#   → Stop all from the runtime notification (no processes left) → uninstall from the detail page.
# x86_64 KVM emulator: uses the x86_64 Termux proot + amd64 Debian; arm64-only bits are proven on the arm64 runner.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$(find "$ROOT/apks" "$ROOT/app/build/outputs" -name app-debug.apk 2>/dev/null | head -1)}"; [ -f "$APK" ] || { echo "no apk"; exit 1; }; echo "Using $APK"; APP="${E2E_APP:-mousepad}"
OUT="${PROOF_DIR:-$ROOT/harness-out/emu}"; mkdir -p "$OUT"
UI="python3 $ROOT/scripts/ui.py"
t0=$(date +%s); stamp() { echo "[$(( $(date +%s) - t0 ))s] $*"; }
# Foldables have several displays: screencap prefixes a warning, so keep only the bytes from the PNG signature on.
shot() { adb exec-out screencap -p | python3 -c 'import sys; b=sys.stdin.buffer.read(); sys.stdout.buffer.write(b[b.find(b"\x89PNG"):])' > "$OUT/$1.png"; stamp "screenshot $1 ($(stat -c%s "$OUT/$1.png") bytes)"; }
fail() { stamp "FAIL: $*"; shot zz-fail; $UI dump || true; adb logcat -d -s HatchRuntime HatchSetup HatchInstall HatchStub HatchWindow HatchSession HatchGuest HatchAudio HatchFloat AndroidRuntime:E > "$OUT/logcat.txt" 2>&1; tail -60 "$OUT/logcat.txt"; adb logcat -d -b crash > "$OUT/crash.txt" 2>&1; adb logcat -d -b main,system | grep -E 'DEBUG|libc|dev\.hatch.*(died|Kill)|lowmemorykiller|ANR in' | tail -80 > "$OUT/am.txt" 2>&1; head -40 "$OUT/crash.txt"; exit 1; }

# Hide the soft keyboard only if it's showing (BACK with no IME would leave Hatch, e.g. on large screens).
hide_ime() { for k in 1 2 3; do adb shell dumpsys input_method | grep -q 'mInputShown=true' || return 0; adb shell input keyevent 4; sleep 1; done; }
# Type into the store search field and verify it (keystrokes can race the field's focus: "omusepad").
type_search() {
  for t in 1 2 3 4; do
    $UI tap 'Search apps|Search' dev.hatch || true; sleep 2
    adb shell input keyevent $(printf '67 %.0s' $(seq 1 20)); sleep 0.5
    adb shell input text "$1"; sleep 2
    $UI has "^$1\$" dev.hatch && return 0
    stamp "search field mistyped (try $t), retrying"
  done
  return 1
}
# Another ordinary Android app to float over. Not Settings: it sets HIDE_NON_SYSTEM_OVERLAY_WINDOWS, so Android
# hides every third-party overlay above it by design.
other_app() { adb shell am start -W -a android.intent.action.DIAL >/dev/null 2>&1 || adb shell am start -W -a android.intent.action.VIEW -d https://example.org >/dev/null; }
# The launcher's "Add to home screen" sheet for Hatch's pinned shortcut (Pixel: "Add to home screen"; One UI: "Add").
PINRX='^(Add to home screen|Add automatically|ADD AUTOMATICALLY|Add)$'
pin_confirm() { # [screenshot-name]
  for k in $(seq 1 8); do
    if $UI has "$PINRX" 'launcher|nexus|systemui'; then
      [ -n "${1:-}" ] && shot "$1"
      $UI tap "$PINRX" 'launcher|nexus|systemui' && { sleep 2; return 0; }
    fi
    sleep 2
  done; return 1
}
# Wait for the store's Open button after Install (failure → Details shot). $1 = proof prefix.
wait_installed() {
  for i in $(seq 1 ${2:-300}); do
    $UI has '^Open$' dev.hatch && return 0
    # Success also raises the launcher's pin sheet, which covers the store (the dump then has no Open button).
    $UI has "$PINRX" 'launcher|nexus|systemui' && return 0
    if $UI has "Couldn.t install|Install failed" dev.hatch; then
      $UI tap '^Details$' dev.hatch || true; sleep 2; shot "$1-install-failed"
      adb logcat -d -s HatchInstall > "$OUT/$1-install-logcat.txt" 2>&1; return 1
    fi
    [ $((i % 12)) -eq 0 ] && stamp "install running ($((i / 12)) min): $($UI dump 2>/dev/null | grep -m1 -E ' — ' | cut -c1-120)"
    [ $((i % 24)) -eq 12 ] && { stamp "installer log so far:"; adb logcat -d -s HatchInstall HatchGuest | tail -8 | cut -c1-200 | sed 's/^/  /'; }
    sleep 5
  done
  stamp "install of $1 timed out; diagnostics:"
  shot "$1-install-timeout"
  adb logcat -d -s HatchInstall HatchGuest HatchRuntime HatchSetup > "$OUT/$1-install-logcat.txt" 2>&1; tail -40 "$OUT/$1-install-logcat.txt" | cut -c1-220
  HU=$(adb shell ps -A -o USER,NAME | awk '$2=="dev.hatch"{print $1}' | tr -d '\r' | head -1)
  adb shell ps -A -o USER,PID,PPID,STAT,TIME,ARGS | tr -d '\r' | awk -v u="$HU" '$1==u' | cut -c1-200 | tee "$OUT/$1-install-ps.txt"
  return 1
}
# Start an app the way its Home screen shortcut does (same intent, flags NEW_TASK|MULTIPLE_TASK|NEW_DOCUMENT).
launch_intent() { adb shell am start -a android.intent.action.VIEW -d "hatch://app/$1" -n dev.hatch/.ui.WindowActivity --es appId "$1" --es label "'$2'" -f 0x18080000 >/dev/null; }
# Tap the app's icon on the Home screen; falls back to the shortcut intent if the launcher put it elsewhere.
launch_app() {
  adb shell input keyevent 3; sleep 3
  if $UI tap "^$2\$" 'launcher|nexus'; then stamp "launched $2 from its Home screen icon"; else stamp "WARN: no Home screen icon labelled $2; using its shortcut intent"; launch_intent "$1" "$2"; fi
}
app_name() { python3 -c 'import json,sys; print([a for a in json.load(open(sys.argv[1]))["apps"] if a["id"]==sys.argv[2]][0]["name"])' "$ROOT/catalogue/catalogue.json" "$1"; }

# Install a catalogue app through the store UI (search → result → detail → Install → pin sheet). $1 = app id.
store_install() {
  local id=$1 name
  name=$(python3 -c 'import json,sys; print([a for a in json.load(open(sys.argv[1]))["apps"] if a["id"]==sys.argv[2]][0]["name"])' "$ROOT/catalogue/catalogue.json" "$id")
  adb shell am start -W -n dev.hatch/.ui.MainActivity >/dev/null; sleep 3
  # The store may resume on a detail page (no tab bar): back out to the tabs first.
  for k in 1 2 3; do $UI has '^Get apps$' dev.hatch && break; hide_ime; adb shell input keyevent 4; sleep 2
    adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q dev.hatch || adb shell am start -W -n dev.hatch/.ui.MainActivity >/dev/null; done
  $UI tap '^Get apps$' dev.hatch || true; sleep 1
  type_search "$id" || return 1
  hide_ime; sleep 1
  for t in 1 2 3 4; do $UI has '^Install$' dev.hatch && break; $UI tap "^$name\$" dev.hatch || true; sleep 2; done
  $UI tap '^Install$' dev.hatch || return 1
  wait_installed "$id" 120 || return 1
  pin_confirm || stamp "WARN: no pin sheet for $id"
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
  adb shell "run-as dev.hatch sh -c 'mkdir -p files/tmp files/linux/tmp files/linux/.l2s'"   # the app makes these lazily
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
$UI wait '^Set up Hatch$' 60 || fail "no welcome screen"
shot 01-welcome-folded
# Same screen on the Fold's inner display size (the emulator profile boots folded).
adb shell wm size 2208x1840; sleep 3; shot 01b-welcome-unfolded; adb shell wm size reset; sleep 3
SETUP_T0=$(date +%s)
$UI tap '^Set up Hatch$' dev.hatch || fail "tap setup"
# A tap during cold start can be dropped (button still showing, nothing started): re-tap a few times.
for k in 1 2 3; do sleep 4; $UI has '^Set up Hatch$' dev.hatch || break; stamp "setup tap not taken, re-tapping ($k)"; $UI tap '^Set up Hatch$' dev.hatch || true; done
# Permission steps are skipped here (granted above); the screenshot tests render them.
for k in 1 2 3; do $UI has '^Not now$|^Use full-screen apps$' dev.hatch || break; $UI tap '^Not now$|^Use full-screen apps$' dev.hatch; sleep 1; done
sleep 3; shot 02-setup-progress-folded
adb shell wm size 2208x1840; sleep 3; shot 02b-setup-progress-unfolded; adb shell wm size reset; sleep 2
$UI tap '^Details$' dev.hatch && { sleep 1; shot 02c-setup-details; $UI tap '^Hide details$' dev.hatch || true; }
# First run: download rootfs + apt; log what the setup screen says every minute so a slow/failed step is visible.
ok=0; retried=0
for i in $(seq 1 25); do
  if $UI wait 'Search apps' 60 >/dev/null 2>&1; then ok=1; break; fi
  screen=$($UI dump 2>/dev/null | grep dev.hatch | cut -d: -f2- | tr '\n' '|')
  stamp "setup still running ($i min): $(echo "$screen" | cut -c1-300)"
  [ $((i % 5)) -eq 0 ] && shot "02-setup-${i}min"
  if echo "$screen" | grep -q "Setup didn.t finish"; then
    shot "02-setup-failed-$retried"
    stamp "setup reported a failure: $(echo "$screen" | cut -c1-300)"
    # A user would tap Try again: do that once (proves the retry path), then give up.
    [ $retried = 0 ] || break
    retried=1; $UI tap '^Try again$' || break
  fi
done
SETUP_SECS=$(( $(date +%s) - SETUP_T0 ))
adb logcat -d -s HatchSetup HatchGuest HatchRootfs > "$OUT/setup-logcat.txt" 2>&1 || true
stamp "setup log: $(grep -c . "$OUT/setup-logcat.txt") lines; $(grep -cE 'Temporary failure|Could not resolve' "$OUT/setup-logcat.txt") DNS errors"
stamp "SETUP TIME (tap → store): ${SETUP_SECS}s; app timings: $(grep -o 'setup timing: .*' "$OUT/setup-logcat.txt" | tr '\n' ';')"
echo "setup_seconds=$SETUP_SECS" > "$OUT/setup-timing.txt"; grep -o 'setup timing: .*' "$OUT/setup-logcat.txt" >> "$OUT/setup-timing.txt" || true
guest_probe
[ $ok = 1 ] || fail "setup did not reach store"
shot 03-store

stamp "search + install $APP"
type_search "$APP" || fail "could not type the search"
adb shell input keyevent 111 || true   # hide IME
shot 04-search
APPNAME=$(python3 -c 'import json,sys; print([a for a in json.load(open(sys.argv[1]))["apps"] if a["id"]==sys.argv[2]][0]["name"])' "$ROOT/catalogue/catalogue.json" "$APP")
hide_ime; sleep 1   # make sure the soft keyboard is gone before tapping the result
$UI tap "^$APPNAME\$" dev.hatch || fail "no search result for $APPNAME"
sleep 2
# The first tap can be swallowed while the IME animates away; tap again until the detail page's Install button shows.
for t in 1 2 3; do $UI has '^Install$' dev.hatch && break; $UI tap "^$APPNAME\$" dev.hatch || true; sleep 2; done
shot 04b-detail
$UI tap '^Install$' dev.hatch || fail "no Install button"
sleep 6; shot 04c-installing
# Installed → Hatch asks the launcher to pin the app's icon: a shortcut, so no APK and no Play Protect scan.
wait_installed 05 120 || { guest_probe; fail "install failed in store"; }
stamp "installed; launcher pin sheet:"
pin_confirm 05-pin-sheet || stamp "WARN: no launcher pin sheet (launcher may have added it silently)"
sleep 2; shot 06-store-installed
adb shell dumpsys shortcut > "$OUT/shortcuts.txt" 2>&1
grep -q "app:$APP" "$OUT/shortcuts.txt" || fail "no launcher shortcut for $APP"
stamp "shortcut: $(grep -m1 "app:$APP" "$OUT/shortcuts.txt" | cut -c1-200)"
adb shell input keyevent 3; sleep 3; shot 07-home-shortcut

stamp "Home screen icon → program window"
adb logcat -c
launch_app "$APP" "$APPNAME"
for i in $(seq 1 120); do adb logcat -d | grep -qE 'windowCreated|window created' && break; sleep 2; done
adb logcat -d | grep -qE 'windowCreated|window created' || fail "no Wayland window created"
sleep 8
shot 08-app-window
adb shell dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity' | head -3
[ "${E2E_MODE:-}" = blender ] || python3 - "$OUT/08-app-window.png" <<'PY' || fail "window looks empty"
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB"); w, h = im.size
crop = im.crop((0, h // 8, w, h * 7 // 8)); px = list(crop.getdata())[::97]
u = len(set(px)); print(f"app window uniq colours={u}"); sys.exit(0 if u > 12 else 1)
PY

if [ "${E2E_MODE:-}" = blender ]; then
  # Blender (X11 via rootful Xwayland, llvmpipe) as a floating window over the home screen, then the store on top.
  stamp "blender: floating over home"
  adb shell input keyevent 3; sleep 3
  for i in $(seq 1 60); do float_frame Blender >/dev/null && break; sleep 5; done
  F=$(float_frame Blender) || fail "Blender is not a floating overlay window"
  stamp "blender overlay frame: $F (screen $(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1))"
  sleep 150   # first start compiles shaders on llvmpipe
  shot B1-blender-floating-over-home
  adb shell am start -W -n dev.hatch/.ui.MainActivity >/dev/null; sleep 5
  float_frame Blender >/dev/null || fail "Blender window closed when the store opened"
  shot B2-blender-floating-with-store
  adb shell input keyevent 3; sleep 3
  shot B3-blender-floating-over-home-later
  adb logcat -d -s HatchWindow HatchFloat HatchSession > "$OUT/logcat.txt" 2>&1
  stamp "blender floating proof done"; exit 0
fi

stamp "floating window checks"
adb shell input keyevent 3; sleep 3   # home: the overlay must stay above the launcher
F=$(float_frame Mousepad) || fail "no floating overlay window 'Hatch: Mousepad' (dumpsys window)"
stamp "overlay frame: $F"
adb shell dumpsys activity recents | grep -q "dev.hatch.app.mousepad\|WindowActivity" && stamp "WARN: program left a Recents task" || stamp "no per-program Recents card (single Hatch card)"
shot 08b-floating-over-home
read L T R B <<<"$F"; SH=$(( (B - T) / 60 ))   # shadow ~10dp, handle 26dp; approx via density
D=$(adb shell wm density | grep -o '[0-9]*' | tail -1); dp() { echo $(( $1 * D / 160 )); }
HX=$(( (L + R) / 2 )); HY=$(( T + $(dp 10) + $(dp 13) ))
# Drag up-left (keeps the whole window on screen, so the resize corner stays reachable on any profile).
adb shell input swipe $HX $HY $((HX - 60)) $((HY - 200)) 600; sleep 2
F2=$(float_frame Mousepad); stamp "after drag-move: $F2"; read L2 T2 R2 B2 <<<"$F2"
[ $((T - T2)) -gt 150 ] || fail "drag on the handle did not move the window ($F → $F2)"
CX=$(( R2 - $(dp 10) - $(dp 8) )); CY=$(( B2 - $(dp 10) - $(dp 8) ))
adb shell input swipe $CX $CY $((CX - 200)) $((CY - 250)) 700; sleep 3
F3=$(float_frame Mousepad) || fail "floating window vanished after corner resize"; stamp "after corner resize: $F3"; read L3 T3 R3 B3 <<<"$F3"
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

hide_ime; sleep 1   # done typing: the keyboard shouldn't cover the next proofs
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

stamp "floating over another Android app (Phone dialer)"
other_app; sleep 4
float_frame Mousepad >/dev/null || fail "floating window hidden by another app"
shot 10c-floating-over-another-app

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
adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q 'dev.hatch/.ui.MainActivity' || fail "Open Hatch from the bubble did not show the store"
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
other_app; sleep 3
adb logcat -c
launch_intent galculator "$(app_name galculator)"
for i in $(seq 1 60); do float_frame Calculator >/dev/null && break; sleep 2; done
float_frame Calculator >/dev/null || fail "Calculator (galculator) did not open as a floating window"
sleep 6
GF=$(float_frame Calculator); MF=$(float_frame Mousepad) || fail "mousepad window gone when galculator opened"
stamp "two floating windows: Mousepad [$MF] Galculator [$GF]"
[ "$GF" != "$MF" ] || fail "windows not cascaded"
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity' | grep -qiE 'dialer|contacts|phone' && stamp "Phone app is the resumed app underneath" || stamp "WARN: Phone app not on top underneath"
shot P2-two-windows-over-another-app

stamp "P3: store opened while both windows float"
adb shell am start -W -n dev.hatch/.ui.MainActivity >/dev/null; sleep 4
float_frame Mousepad >/dev/null && float_frame Calculator >/dev/null || fail "a window closed when the store opened"
shot P3-store-with-two-floating-windows

stamp "P4: maximise then restore (Mousepad)"
adb shell input keyevent 3; sleep 2
F=$(float_frame Mousepad); read L T R B <<<"$F"
adb logcat -c
adb shell input tap $(( (L + R) / 2 )) $(( T + $(dp 10) + $(dp 13) )); sleep 2
tap_target "control Maximise" Mousepad || { sleep 1; adb shell input tap $(( (L + R) / 2 )) $(( T + $(dp 10) + $(dp 13) )); sleep 2; tap_target "control Maximise" Mousepad; } || fail "no Maximise control"
sleep 3; MX=$(float_frame Mousepad); read ML MT MR MB <<<"$MX"
stamp "maximised frame: $MX (screen ${WX}x${WY})"
[ $(( MR - ML )) -ge $(( WX * 95 / 100 )) ] || fail "maximise did not fill the width ($F → $MX)"
[ "$MB" -le $(( WY + $(dp 12) )) ] || fail "maximised window runs off the bottom ($MX, screen height $WY)"
shot P4a-maximised
adb logcat -c
adb shell input tap $(( (ML + MR) / 2 )) $(( (MT > 0 ? MT : 0) + $(dp 10) + $(dp 13) )); sleep 2
tap_target "control Maximise" Mousepad || fail "no Maximise control on the maximised window"
sleep 3; RF=$(float_frame Mousepad); stamp "restored frame: $RF (was $F)"
[ "$RF" = "$F" ] || fail "restore did not return to the floating bounds ($F → $RF)"
shot P4b-restored
adb logcat -d -s HatchRuntime HatchWindow HatchSession > "$OUT/logcat.txt" 2>&1
stamp "mousepad leg OK"

stamp "one-tap close (✕ in the caption): unfocused Calculator, then focused Mousepad"
# Caption (34dp, inside the 10dp shadow): … ⋮ — ⤢ ✕ at 42dp each from the right edge.
close_xy() { echo $(( $3 - $(dp 10) - $(dp 21) )) $(( $2 + $(dp 10) + $(dp 17) )); }
min_xy() { echo $(( $3 - $(dp 10) - $(dp 105) )) $(( $2 + $(dp 10) + $(dp 17) )); }
proc_alive() { adb shell ps -A -o NAME | tr -d '\r' | grep -qx "$1"; }
ms_since() { echo $(( ($(date +%s%N) - $1) / 1000000 )); }
# Wait up to $2 ms for window "$1" to leave the screen; prints elapsed ms
wait_gone() { local t0=$3; while [ "$(ms_since $t0)" -lt "$2" ]; do float_frame "$1" >/dev/null || { ms_since $t0; return 0; }; sleep 0.1; done; return 1; }
read ML MT MR MB <<<"$(float_frame Mousepad)" || fail "no Mousepad window before close test"
read GL GT GR GB <<<"$(float_frame Calculator)" || fail "no Calculator window before close test"
adb shell input tap $(( (ML + MR) / 2 )) $(( (MT + MB) / 2 )); sleep 1     # Mousepad focused → Calculator is not
read CX CY <<<"$(close_xy $GL $GT $GR)"
PARKED=""
if [ $CX -ge $ML ] && [ $CX -le $MR ] && [ $CY -ge $MT ] && [ $CY -le $MB ]; then
  read NX NY <<<"$(min_xy $ML $MT $MR)"; adb shell input tap $NX $NY; sleep 2   # Calculator's ✕ is under Mousepad: park it
  float_frame Mousepad >/dev/null && fail "Mousepad's caption — didn't minimise it"
  PARKED=1
fi
proc_alive galculator || fail "galculator not running before the close test"
adb logcat -c; shot 15a-before-one-tap-close
T0=$(date +%s%N); adb shell input tap $CX $CY
GONE=$(wait_gone Calculator 3000 $T0) || { shot 15b-close-failed; adb logcat -d -s HatchFloat HatchRuntime | tail -20; fail "Calculator still on screen 3 s after ONE tap on ✕ (unfocused window)"; }
stamp "unfocused Calculator: window gone ${GONE} ms after one tap on ✕"
for i in $(seq 1 25); do proc_alive galculator || break; sleep 0.2; done
proc_alive galculator && fail "galculator still running 5 s after one tap on ✕"
adb logcat -d -s HatchFloat | grep -E "close (tapped|galculator)" | sed 's/^.*HatchFloat[^:]*: /  /'
stamp "galculator session ended"

if [ -n "$PARKED" ]; then
  read BL BT BR BB <<<"$(win_frame 'Hatch bubble')" || fail "bubble missing"
  adb logcat -c; adb shell input tap $(( (BL + BR) / 2 )) $(( (BT + BB) / 2 )); sleep 2
  tap_target "bubble-menu ▢  Mousepad" bubble || fail "bubble menu has no Mousepad"; sleep 2
fi
read ML MT MR MB <<<"$(float_frame Mousepad)" || fail "Mousepad not on screen for the focused close"
adb shell input tap $(( (ML + MR) / 2 )) $(( (MT + MB) / 2 )); sleep 1     # focused
read CX CY <<<"$(close_xy $ML $MT $MR)"
adb logcat -c; T0=$(date +%s%N); adb shell input tap $CX $CY
if GONE=$(wait_gone Mousepad 3000 $T0); then
  stamp "focused Mousepad: window gone ${GONE} ms after one tap on ✕"
elif adb logcat -d -s HatchFloat | grep -q "close mousepad called off"; then
  # Unsaved text from the typing check: Mousepad answered with its own "Save changes?" dialog, which Hatch shows.
  sleep 1; shot 15c-close-save-changes-dialog
  stamp "focused Mousepad answered ✕ with its own dialog (kept on screen, as intended)"
  read ML MT MR MB <<<"$(adb shell dumpsys window windows | python3 -c '
import re,sys
t=sys.stdin.read(); best=None
for m in re.finditer(r"Hatch: Mousepad", t):
    f=re.search(r"frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", t[m.end():m.end()+6000])
    if f:
        b=tuple(map(int,f.groups())); a=(b[2]-b[0])*(b[3]-b[1])
        if best is None or a>best[0]: best=(a,b)
print(*best[1])')"
  read CX CY <<<"$(close_xy $ML $MT $MR)"
  T0=$(date +%s%N); adb shell input tap $CX $CY
  GONE=$(wait_gone Mousepad 3000 $T0) || { shot 15d-close-failed; fail "Mousepad still on screen 3 s after ✕ with its dialog up"; }
  stamp "Mousepad (dialog up): closed ${GONE} ms after ✕"
else
  shot 15d-close-failed; adb logcat -d -s HatchFloat HatchRuntime | tail -20
  fail "Mousepad still on screen 3 s after ONE tap on ✕ (focused window)"
fi
for i in $(seq 1 25); do proc_alive mousepad || break; sleep 0.2; done
proc_alive mousepad && fail "mousepad still running 5 s after close"
shot 15e-after-one-tap-close
adb logcat -d -s HatchFloat | grep -E "close (tapped|mousepad)|hidden for close|called off" | sed 's/^.*HatchFloat[^:]*: /  /'
stamp "one-tap close OK"

if [ "${E2E_WINE:-1}" = 1 ]; then
  stamp "Windows app via Wine: Notepad++ (x64 build on this x86_64 emulator; ARM64 build is proven on the arm64 runner)"
  adb shell am start -S -W -n dev.hatch/.ui.MainActivity >/dev/null
  $UI wait 'Search apps' 60 || fail "store not shown again"
  $UI tap 'Search apps' && adb shell input text "notepad" && sleep 2
  adb shell input keyevent 111 || true
  shot 11-search-notepadpp
  $UI tap '^Notepad\+\+$' dev.hatch || fail "no search result for Notepad++"
  sleep 2; shot 11b-notepadpp-detail
  $UI tap '^Install$' dev.hatch || fail "no Install button for Notepad++"
  wait_installed 12-wine 360 || fail "Notepad++ install failed"
  pin_confirm 12-wine-pin-sheet || stamp "WARN: no pin sheet for Notepad++"
  stamp "Notepad++ installed"
  guest 'mkdir -p /etc/hatch && echo "+timestamp,+loaddll,+process,err" > /etc/hatch/winedebug' >/dev/null 2>&1 || true
  adb logcat -c; adb logcat -b crash -c 2>/dev/null || true
  launch_intent notepadpp "Notepad++"
  # First run creates the Wine prefix (wineboot) under proot, which is slow on the emulator: allow 20 min, log progress.
  NPP="bound target.*Notepad\+\+|title '[^']*Notepad\+\+"
  for i in $(seq 1 400); do
    adb logcat -d -s HatchWindow HatchRuntime | grep -qE "$NPP" && break
    adb logcat -d -s HatchSession | grep -q "notepadpp exited" && break
    if adb logcat -d -b crash 2>/dev/null | grep -q '>>> dev.hatch <<<'; then
      adb logcat -d -b crash > "$OUT/wine-hatch-crash.txt" 2>&1
      grep -m3 -E 'Abort message|signal [0-9]+|dev\.hatch\.' "$OUT/wine-hatch-crash.txt"
      fail "Hatch itself crashed while Wine was running (see wine-hatch-crash.txt)"
    fi
    if [ $((i % 40)) -eq 0 ]; then
      stamp "waiting for Notepad++ ($((i * 3 / 60)) min): $(adb shell run-as dev.hatch tail -c 300 files/logs/notepadpp.log 2>/dev/null | tr '\n' ' ' | tail -c 200)"
      shot "12b-wine-wait-$((i * 3 / 60))min"
    fi
    sleep 3
  done
  adb logcat -d -s HatchRuntime HatchWindow HatchSession > "$OUT/wine-logcat.txt" 2>&1
  adb shell run-as dev.hatch cat files/logs/notepadpp.log > "$OUT/wine-app-output.txt" 2>&1 || true
  if ! grep -qE "$NPP" "$OUT/wine-logcat.txt"; then
    { adb shell ps -A; adb shell 'for p in /proc/[0-9]*; do c=$(tr "\0" " " < $p/cmdline 2>/dev/null); case "$c" in *wine*|*.exe*) echo "${p#/proc/} $(cut -d" " -f3 $p/stat) $c";; esac; done'; } 2>/dev/null | grep -iE 'wine|\.exe|proot| [RSD] ' > "$OUT/wine-ps.txt" || true
    adb shell run-as dev.hatch ls -la files/linux/home/hatch/.wine files/linux/home/hatch/.wine/drive_c/windows 2>&1 | head -40 > "$OUT/wine-prefix.txt" || true
    tail -30 "$OUT/wine-app-output.txt"; cat "$OUT/wine-ps.txt"
    fail "no Notepad++ window (see wine-app-output.txt, wine-logcat.txt, wine-ps.txt)"
  fi
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

# DOOM (once the bundled catalogue has it): install from the store, reach E1M1 with keys, then play with the
# on-screen pad (hold the D-pad, hold FIRE); frames must not be black; one tap on ✕ closes it.
frame_ok() { # png l t r b → non-black, non-flat crop
  python3 - "$@" <<'PY'
import sys
from PIL import Image, ImageStat
p, l, t, r, b = sys.argv[1], *map(int, sys.argv[2:6])
im = Image.open(p).convert("L").crop((l, t + 80, r, b))
st = ImageStat.Stat(im); print(f"mean={st.mean[0]:.1f} stddev={st.stddev[0]:.1f}")
sys.exit(0 if st.stddev[0] >= 8 and st.mean[0] >= 12 else 1)
PY
}
touch_xy() { adb logcat -d -s HatchFloat | grep -F "touch Touch $1 at" | grep -F "for '$2'" | tail -1 | sed -E 's/.* at ([0-9]+) ([0-9]+) for.*/\1 \2/'; }
if python3 -c 'import json,sys; sys.exit(0 if any(a["id"]=="doom" for a in json.load(open(sys.argv[1]))["apps"]) else 1)' "$ROOT/catalogue/catalogue.json"; then
  stamp "DOOM: install from the store"
  store_install doom || fail "DOOM install"
  $UI has '^Import WAD$' dev.hatch && stamp "Import WAD button shown" || fail "no Import WAD button on DOOM's detail page"
  shot 30-doom-detail
  guest 'ls -la /opt/hatch/doom /usr/share/games/doom 2>&1; sha256sum /opt/hatch/doom/doom1.wad 2>&1' | sed 's/^/  /' | tee "$OUT/doom-wads.txt"
  grep -q '^  1d7d43be501e67d927e415e0b8f3e29c3bf33075e859721816f652a526cac771' "$OUT/doom-wads.txt" && stamp "shareware DOOM1.WAD installed (sha256 OK)" || stamp "WARN: no shareware WAD; Freedoom fallback"
  adb logcat -c
  launch_intent doom DOOM
  for i in $(seq 1 60); do DF=$(float_frame DOOM) && break; sleep 2; done
  [ -n "${DF:-}" ] || fail "DOOM window never appeared"
  sleep 12; read DL DT DR DB <<<"$DF"; stamp "DOOM window $DF"
  shot 31-doom-title
  adb shell input tap $(( (DL + DR) / 2 )) $(( (DT + DB) / 2 )); sleep 1     # focus the window (keys go to the focused overlay)
  for k in 1 2 3 4; do adb shell input keyevent KEYCODE_ENTER; sleep 1.2; done  # menu → New Game → episode → skill
  sleep 3; shot 32-doom-e1m1
  frame_ok "$OUT/32-doom-e1m1.png" $DL $DT $DR $DB || fail "DOOM E1M1 frame is black/flat"
  if [ -z "$(touch_xy FIRE DOOM)" ]; then   # pad hidden (emulator reports a keyboard): window menu → Touch controls
    tap_target "control Window menu" DOOM || fail "no window menu on DOOM"; sleep 1
    tap_target "menu Touch controls" DOOM || fail "no Touch controls item in DOOM's window menu"; sleep 2
  fi
  PAD=$(touch_xy D-pad DOOM); FIRE=$(touch_xy FIRE DOOM)
  [ -n "$PAD" ] && [ -n "$FIRE" ] || fail "touch controls not shown"
  read PX PY <<<"$PAD"; read FX FY <<<"$FIRE"
  stamp "touch pad: D-pad $PAD, FIRE $FIRE"
  adb shell input swipe $PX $(( PY - $(dp 40) )) $PX $(( PY - $(dp 41) )) 1500   # hold up: walk forward
  adb shell input swipe $FX $FY $FX $FY 700                                      # hold FIRE
  adb shell input swipe $(( PX + $(dp 40) )) $PY $(( PX + $(dp 41) )) $PY 500     # turn right
  sleep 1; shot 33-doom-touch-gameplay
  frame_ok "$OUT/33-doom-touch-gameplay.png" $DL $DT $DR $DB || fail "DOOM gameplay frame is black/flat"
  cmp -s "$OUT/32-doom-e1m1.png" "$OUT/33-doom-touch-gameplay.png" && fail "touch controls changed nothing"
  T0=$(date +%s%N); read CX CY <<<"$(close_xy $DF)"; adb shell input tap $CX $CY
  wait_gone DOOM 3000 $T0 >/dev/null || fail "DOOM still on screen 3 s after one tap on ✕"
  stamp "DOOM leg OK"
fi

stamp "Stop all from the runtime notification"
HUSER=$(adb shell ps -A -o USER,NAME | awk '$2=="dev.hatch"{print $1}' | tr -d '\r' | head -1)
guest_procs() { adb shell ps -A -o USER,PID,NAME | tr -d '\r' | awk -v u="$HUSER" '$1==u && $3!="dev.hatch"'; }
stamp "app uid $HUSER; guest processes before: $(guest_procs | wc -l)"
float_frame Mousepad >/dev/null || { launch_intent "$APP" "$APPNAME"; sleep 15; }
adb shell cmd statusbar expand-notifications; sleep 3
if ! $UI has '^(Stop all|STOP ALL)$' systemui; then
  # Collapsed notification: drag it down to expand its actions.
  XY=$($UI xy 'Hatch runtime' systemui) && { read NX NY <<<"$XY"; adb shell input swipe $NX $NY $NX $((NY + 400)) 400; sleep 2; }
fi
shot 21-runtime-notification
$UI has 'apps? running' systemui && stamp "notification: $($UI dump | grep -m1 -oE "[0-9]+ apps? running[^']*")" || stamp "WARN: running-apps text not found"
$UI tap '^(Stop all|STOP ALL)$' systemui || fail "no Stop all action on the runtime notification"
sleep 8; adb shell cmd statusbar collapse; sleep 2
shot 22-after-stop-all
adb shell dumpsys activity services dev.hatch | grep -q 'HatchRuntimeService' && fail "runtime service still running after Stop all"
LEFT=$(guest_procs); [ -z "$LEFT" ] || { echo "$LEFT"; fail "guest processes left after Stop all"; }
float_frame Mousepad >/dev/null && fail "floating window left after Stop all"
win_frame "Hatch bubble" >/dev/null && fail "bubble left after Stop all"
adb shell dumpsys power | sed -n '/Wake Locks:/,/^$/p' | grep -q 'dev.hatch' && fail "wakelock held after Stop all"
adb shell dumpsys notification | grep -q 'pkg=dev.hatch.*id=42' && fail "runtime notification still shown"
stamp "stop all OK: no service, guest processes, overlays, wakelocks or notification"

stamp "uninstall galculator from its detail page"
adb shell am start -W -n dev.hatch/.ui.MainActivity >/dev/null; sleep 3
for k in 1 2 3; do $UI has '^Get apps$' dev.hatch && break; hide_ime; adb shell input keyevent 4; sleep 2; done
$UI tap '^Get apps$' dev.hatch || true; sleep 1
type_search galculator || fail "search for galculator"
hide_ime; sleep 1
GNAME=$(app_name galculator)
for t in 1 2 3 4; do $UI has '^Open$' dev.hatch && break; $UI tap "^$GNAME\$" dev.hatch || true; sleep 2; done
shot 23-detail-installed
$UI tap 'More actions' dev.hatch || fail "no More actions on the detail page"; sleep 1
$UI tap '^Uninstall$' dev.hatch || fail "no Uninstall menu item"
$UI wait 'Frees about|Removes the app' 120 dev.hatch || fail "no uninstall confirmation with the space freed"
shot 24-uninstall-confirm
stamp "confirm: $($UI dump | grep -m1 -oE "Frees about [^']*|Removes the app[^']*")"
$UI tap '^Uninstall$' dev.hatch || fail "no Uninstall confirm button"
$UI wait '^Install$' 300 dev.hatch || { shot 25-uninstall-failed; fail "store did not return to Install after uninstall"; }
$UI has "Couldn.t uninstall|REQUEST_DELETE_PACKAGES|Exception" dev.hatch && { shot 25-uninstall-failed; fail "uninstall showed an error"; }
shot 25-uninstalled
guest 'dpkg-query -W -f="\${Status}" galculator 2>/dev/null | grep -q "install ok installed" && echo STILL-INSTALLED || echo REMOVED' | tee "$OUT/uninstall-dpkg.txt" | grep -q REMOVED || fail "galculator still installed in Debian"
guest 'dpkg-query -W -f="\${Status}\n" mousepad libgtk-3-0t64 2>/dev/null' | grep -c 'install ok installed' | grep -qx 2 || fail "uninstall removed packages other apps need"
adb shell run-as dev.hatch cat files/installed.json | grep -q '"galculator"' && fail "galculator still recorded as installed"
stamp "shortcut after uninstall: $(adb shell dumpsys shortcut | grep -m1 'app:galculator' | cut -c1-200 || echo none)"
stamp "uninstall OK"

# Store tour (C1-C10 screenshots): Apps grid, Installed, Updates, Settings, and the Experimental Steam entry.
tour() { # shot-name label-regex
  if $UI tap "$2" dev.hatch; then sleep 2; shot "$1"; stamp "tour $1 OK"; else stamp "tour $1: '$2' not found"; fi; }
adb shell am force-stop dev.hatch; sleep 1   # close the floating windows so they do not cover the tour
adb shell monkey -p dev.hatch -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5; hide_ime
tour 15-apps-tab '^Apps$'
tour 16-get-apps '^Get apps$'
tour 17-installed-tab '^Installed$'
tour 18-updates-tab '^Updates$'
tour 19-settings '^Settings$'
$UI tap '^Get apps$' dev.hatch; sleep 2
if $UI tap 'Search apps' dev.hatch || $UI tap 'Search' dev.hatch; then
  adb shell input text "steam"; sleep 2; adb shell input keyevent 111 || true; sleep 1; shot 20-search-steam
  # Steam's native client is ARM64-only ("arches": ["arm64"]), so an x86-64 emulator must not offer it
  if $UI has '^Steam$' dev.hatch; then stamp "WARN: Steam listed on x86-64 (should be arm64-only)"; else stamp "steam hidden on x86-64 OK"; fi
else stamp "tour: search field not found (steam check skipped)"; fi
stamp "Settings → Remove Linux and all apps (type-to-confirm) → back to onboarding"
$UI tap '^Settings$' dev.hatch; sleep 2
for k in 1 2 3 4 5 6; do $UI has '^Remove Linux and all apps$' dev.hatch && break; adb shell input swipe 500 1500 500 500 300; sleep 1; done
shot 26-settings-storage
$UI tap '^Remove Linux and all apps$' dev.hatch || fail "no Remove Linux button in Settings"
$UI wait 'Frees about' 30 dev.hatch || fail "no first reset confirmation"; shot 27-reset-confirm-1
$UI tap '^Continue$' dev.hatch || fail "no Continue"
$UI wait 'Type DELETE to confirm' 10 dev.hatch || fail "no type-to-confirm"
$UI tap 'DELETE' dev.hatch; adb shell input text DELETE; sleep 1; hide_ime; shot 28-reset-type-to-confirm
$UI tap '^Remove everything$' dev.hatch || fail "no Remove everything button"
$UI wait '^Set up Hatch$' 180 dev.hatch || { shot 29-reset-failed; fail "did not return to onboarding after the reset"; }
shot 29-after-reset-onboarding
LEFT=$(adb shell run-as dev.hatch sh -c 'find files cache no_backup -mindepth 1 \( -type f -o -type l \) 2>/dev/null' | tr -d '\r')
[ -z "$LEFT" ] || { echo "$LEFT" | head -20; fail "files left after Remove Linux and all apps"; }
adb shell run-as dev.hatch ls shared_prefs | grep -q hatch.xml || stamp "WARN: prefs not found after reset"
adb shell dumpsys activity services dev.hatch | grep -q HatchRuntimeService && fail "runtime service running after the reset"
stamp "reset OK: only shared_prefs left"
stamp "emu-e2e OK"
