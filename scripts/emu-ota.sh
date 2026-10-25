#!/usr/bin/env bash
# Hatch in-app update (OTA) end-to-end on the emulator, against a FAKE local release feed (no real releases):
#   old build 0.3.0 (code 4) → store start offers 0.3.1 (draft/prerelease/dev entries in the feed ignored) → Later
#   → a Linux app running → Settings → Check for updates → Update → tampered checksum refused → fixed feed → Try again
#   → "Install unknown apps" flow (real Settings toggle) → resume → "Close apps and update" → runtime stopped
#   → Android's update dialog → 0.3.1 running, "Updated" notice → second update 0.3.2 with Hatch as installer of record
#   (no dialog expected) → "You're on the latest version".
# Usage: emu-ota.sh <old.apk> <new.apk> <newer.apk>   (debug builds with -PupdateFeed=http://10.0.2.2:8765/latest.json)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OLD=${1:?old apk}; NEW=${2:?new apk}; NEWER=${3:?newer apk}
OUT="${PROOF_DIR:-$ROOT/harness-out/ota}"; mkdir -p "$OUT"
FEED="${OTA_FEED_DIR:-/tmp/hatch-ota-feed}"; rm -rf "$FEED"; mkdir -p "$FEED"; export OTA_PORT=8765
source "$ROOT/scripts/emu-lib.sh"
fail() { stamp "FAIL: $*"; shot zz-fail; $UI dump || true; adb logcat -d -s HatchUpdate HatchRuntime HatchInstall HatchSession AndroidRuntime:E PackageInstaller:* > "$OUT/logcat.txt" 2>&1; tail -60 "$OUT/logcat.txt"; exit 1; }
ver() { adb shell dumpsys package dev.hatch | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2 | tr -d '\r'; }
code() { adb shell dumpsys package dev.hatch | grep -m1 -o 'versionCode=[0-9]*' | cut -d= -f2; }
installer() { adb shell dumpsys package dev.hatch | grep -m1 -oE 'installerPackageName=[^ ]*' | cut -d= -f2 | tr -d '\r'; }
front() { adb shell am start -W -n dev.hatch/.ui.MainActivity >/dev/null; sleep 3; }
tab() { for k in 1 2 3; do $UI tap "^$1\$" dev.hatch && return 0; adb shell input keyevent 4; sleep 2; front; done; return 1; }
publish() { python3 "$ROOT/scripts/ota-feed.py" publish "$FEED" "$@" | sed 's/^/  feed: /'; }
wait_ver() { for i in $(seq 1 ${2:-60}); do [ "$(ver)" = "$1" ] && return 0; sleep 2; done; return 1; }
# Android's own "update this app?" confirmation (package installer), if it appears within $1 s.
system_confirm() {
  for i in $(seq 1 "$1"); do
    if $UI has '^Update$|^Install$' 'packageinstaller'; then shot "$2"; $UI tap '^Update$|^Install$' 'packageinstaller'; return 0; fi
    [ -n "$(ver)" ] && [ "$(ver)" != "$3" ] && return 1   # already replaced
    sleep 1
  done; return 1
}

python3 "$ROOT/scripts/ota-feed.py" serve "$FEED" $OTA_PORT "${OTA_RATE:-6000000}" 2> "$OUT/feed-server.log" & FEED_PID=$!
trap 'kill $FEED_PID 2>/dev/null' EXIT

adb wait-for-device
for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
adb shell settings put global package_verifier_enable 0 || true
adb shell settings put global verifier_verify_adb_installs 0 || true
adb shell input keyevent 82 || true
adb install -r -g "$OLD" || fail "adb install old"
# REQUEST_INSTALL_PACKAGES deliberately NOT allowed: the test walks the real "Install unknown apps" screen.
adb shell appops set dev.hatch REQUEST_INSTALL_PACKAGES default
adb shell appops set dev.hatch SYSTEM_ALERT_WINDOW allow
adb shell pm grant dev.hatch android.permission.POST_NOTIFICATIONS 2>/dev/null || true
[ "$(ver)" = 0.3.0 ] || fail "old build is $(ver), want 0.3.0"
stamp "installed old Hatch $(ver) (code $(code)), installer=$(installer)"
publish 0.3.1 "$NEW" --bad-sha
adb logcat -c

stamp "first run setup"
front
$UI wait '^Set up Hatch$' 60 || fail "no welcome screen"
$UI tap '^Set up Hatch$' dev.hatch || fail "tap setup"
for k in 1 2 3; do sleep 4; $UI has '^Set up Hatch$' dev.hatch || break; $UI tap '^Set up Hatch$' dev.hatch || true; done
for k in 1 2 3; do $UI has '^Not now$|^Use full-screen apps$' dev.hatch || break; $UI tap '^Not now$|^Use full-screen apps$' dev.hatch; sleep 1; done
ok=0; for i in $(seq 1 25); do $UI wait 'Search apps' 60 >/dev/null 2>&1 && { ok=1; break; }; stamp "setup running ($i min)"
  $UI has "Setup didn.t finish" dev.hatch && { $UI tap '^Try again$' || true; }; done
[ $ok = 1 ] || fail "setup did not reach the store"

stamp "store start: automatic check offers 0.3.1"
$UI wait 'Hatch 0\.3\.1 is available' 60 dev.hatch || fail "no update card after store start"
$UI has '0\.9\.0|0\.4\.0' dev.hatch && fail "a draft/prerelease was offered"
$UI has "What.s new in 0\.3\.1" dev.hatch || fail "release notes not shown"
shot ota-01-card
$UI tap '^Later$' dev.hatch || fail "tap Later"; sleep 2
$UI has 'is available' dev.hatch && fail "card still there after Later"
stamp "Later hides the card"

stamp "start a Linux app so the update has something to stop"
store_install galculator || fail "install galculator"
launch_intent galculator Galculator; sleep 20
shot ota-02-running-app
front

stamp "Settings → Check for updates"
tab Settings || fail "Settings tab"
$UI has 'Automatically check for updates' dev.hatch || fail "no auto-check toggle"
$UI has '^Hatch 0\.3\.0$' dev.hatch || fail "current version not shown"
shot ota-03-settings
$UI tap '^Check for updates$' dev.hatch || fail "tap Check for updates"
$UI wait 'Hatch 0\.3\.1 is available' 30 dev.hatch || fail "manual check didn't offer 0.3.1"

stamp "Update with a tampered checksum: must be refused"
$UI tap '^Update$' dev.hatch || fail "tap Update"
$UI wait 'Downloading' 20 dev.hatch && shot ota-04-progress
$UI wait "didn.t match its checksum" 120 dev.hatch || fail "tampered download not refused"
shot ota-05-bad-checksum
[ "$(ver)" = 0.3.0 ] || fail "something installed despite the bad checksum"

stamp "fixed feed → Try again"
publish 0.3.1 "$NEW"
$UI tap '^Try again$' dev.hatch || fail "tap Try again"
sleep 2; shot ota-06-progress
$UI wait 'Allow installs' 120 dev.hatch || fail "no install-permission step"
shot ota-07-permission
$UI tap '^Allow installs$' dev.hatch || fail "tap Allow installs"
$UI wait 'Allow from this source' 20 || fail "the Install unknown apps screen didn't open"
shot ota-08-unknown-sources
$UI tap 'Allow from this source' || fail "toggle"; sleep 2
adb shell input keyevent 4; sleep 4
adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q dev.hatch || front
if ! $UI wait 'Close apps and update' 20 dev.hatch; then
  # Some Android versions restart an app when this permission changes: the user taps Update again.
  stamp "WARN: no stop-apps prompt after the permission (Hatch restarted?); checking again"
  shot ota-08b-after-permission
  tab Settings; $UI tap '^Check for updates$' dev.hatch; $UI wait 'is available' 30 dev.hatch && $UI tap '^Update$' dev.hatch
  $UI wait 'Close apps and update' 120 dev.hatch || fail "no stop-apps prompt"
fi
$UI has 'Galculator' dev.hatch || fail "running app not named"
shot ota-09-confirm-stop
$UI tap '^Close apps and update$' dev.hatch || fail "tap Close apps and update"
sleep 1; shot ota-10-installing
if system_confirm 60 ota-11-system-dialog 0.3.0; then stamp "Android asked to confirm (expected: adb installed the old build)"; else stamp "no system dialog"; fi
wait_ver 0.3.1 60 || fail "still $(ver) after the update"
stamp "updated to $(ver) (code $(code)), installer now: $(installer)"
adb logcat -d -s HatchUpdate > "$OUT/update1-logcat.txt"
grep -q 'verified hatch-0.3.1.apk: sha256 ok' "$OUT/update1-logcat.txt" || fail "no verification log"
grep -q 'guest processes left=0' "$OUT/update1-logcat.txt" || fail "Linux runtime not stopped cleanly before installing"
grep -q 'service running=false' "$OUT/update1-logcat.txt" || fail "runtime service still running at install"

stamp "relaunch: Updated notice once"
adb logcat -c
front
$UI wait 'Updated to 0\.3\.1' 20 dev.hatch || fail "no Updated notice"
shot ota-12-updated
$UI tap '^OK$' dev.hatch || true

stamp "second update 0.3.2: Hatch is now the installer of record"
publish 0.3.2 "$NEWER"
tab Settings || fail "Settings tab"
$UI tap '^Check for updates$' dev.hatch || fail "tap Check"
$UI wait 'Hatch 0\.3\.2 is available' 30 dev.hatch || fail "0.3.2 not offered"
$UI tap '^Update$' dev.hatch || fail "tap Update"
if system_confirm 60 ota-13-system-dialog-2 0.3.1; then SILENT=no; stamp "Android still asked to confirm"; else SILENT=yes; stamp "installed without a system dialog"; fi
wait_ver 0.3.2 90 || fail "still $(ver) after the second update"
echo "second_update_silent=$SILENT installer=$(installer)" | tee "$OUT/silent.txt"
adb logcat -d -s HatchUpdate > "$OUT/update2-logcat.txt"
front
$UI wait 'Updated to 0\.3\.2' 20 dev.hatch || fail "no Updated notice for 0.3.2"
$UI tap '^OK$' dev.hatch || true
tab Settings
$UI tap '^Check for updates$' dev.hatch || fail "tap Check"
$UI wait "You.re on the latest version" 30 dev.hatch || fail "not up to date"
shot ota-14-up-to-date
stamp "OTA E2E PASSED (0.3.0 → 0.3.1 → 0.3.2, second update silent: $SILENT)"
