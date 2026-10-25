# Shared helpers for the emulator scripts (copied from emu-e2e.sh; needs ROOT and OUT set).
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
