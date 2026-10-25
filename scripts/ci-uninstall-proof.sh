#!/usr/bin/env bash
# Uninstall regression proof (bug: "Couldn't uninstall Steam", apt wanted to remove libudev1 → util-linux / apt).
# Real Debian 13 rootfs under proot (the prebuilt image the app installs, same flags), the app's own
# hatch-install-app / hatch-uninstall-app driven exactly as Uninstall.command builds them (scripts/catalogue-args.py).
# Legs: record check → uninstall Steam with Blender + VS Code installed → 0.2.0-style install (no record) + the
# 0.2.0 uninstall command (must still fail: the reproduction) + the new one → damage recovery → apt failure =
# soft warning → reinstall → uninstall everything. After every uninstall the base system must be intact.
# Env: HATCH_ROOTFS (an unpacked rootfs to use as is; default: fresh prebuilt copy), PROOT, PROOF_DIR, APPS2.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
PROOF="${PROOF_DIR:-$ROOT/harness-out/uninstall}"; mkdir -p "$PROOF"
WORK="${RUNNER_TEMP:-/tmp/hatch-ci}"; mkdir -p "$WORK"
APPS2="${APPS2:-blender code}"   # apps sharing dependencies with Steam that stay installed
t0=$(date +%s); stamp() { echo "[$(( $(date +%s) - t0 ))s] $*"; }
LOG="$PROOF/uninstall-proof.log"; exec > >(tee "$LOG") 2>&1

if [ -z "${PROOT:-}" ]; then
  PROOT_SHA=b27d81a68d765f3462e807f4a14e01a03f8e89ea
  if [ ! -x "$WORK/proot-src/src/proot" ]; then
    stamp "build termux/proot $PROOT_SHA"
    rm -rf "$WORK/proot-src"; git init -q "$WORK/proot-src"
    git -C "$WORK/proot-src" fetch -q --depth 1 https://github.com/termux/proot "$PROOT_SHA"
    git -C "$WORK/proot-src" checkout -q FETCH_HEAD
    make -C "$WORK/proot-src/src" -j"$(nproc)" HAS_LOADER_32BIT= proot >/dev/null 2>&1 || make -C "$WORK/proot-src/src" HAS_LOADER_32BIT= proot
  fi
  export PROOT="$WORK/proot-src/src/proot"
fi
export HATCH_L2S="${HATCH_L2S:-1}"
if [ -z "${HATCH_ROOTFS:-}" ]; then
  export HATCH_ROOTFS="$WORK/rootfs-uninstall"
  ARCH=$([ "$(uname -m)" = aarch64 ] && echo arm64 || echo amd64)
  read -r PURL PSHA <<<"$(python3 - "$ARCH" <<'PY'
import re,sys
s=open("app/src/main/kotlin/dev/hatch/guest/RootfsCatalog.kt").read()
m=re.search(r'"%s" to Prebaked\(\s*"([^"]+)",\s*"([0-9a-f]{64})"' % sys.argv[1], s)
print(m.group(1), m.group(2))
PY
)"
  [ -s "$WORK/rootfs.tar.zst" ] && echo "$PSHA  $WORK/rootfs.tar.zst" | sha256sum -c - >/dev/null 2>&1 || curl -fL --retry 3 -o "$WORK/rootfs.tar.zst" "$PURL"
  echo "$PSHA  $WORK/rootfs.tar.zst" | sha256sum -c -
  chmod -R u+w "$HATCH_ROOTFS" 2>/dev/null || true; rm -rf "$HATCH_ROOTFS"; mkdir -p "$HATCH_ROOTFS"
  zstd -dc "$WORK/rootfs.tar.zst" | tar -x -C "$HATCH_ROOTFS" --no-same-owner --exclude='dev/*' 2>/dev/null || true
  stamp "prebuilt rootfs $ARCH unpacked"
fi
R="$HATCH_ROOTFS"
# Mirror GuestConfig.apply + GuestScripts.install (as ci-guest-proof.sh does)
chmod -R u+w "$R/etc" 2>/dev/null || true
rm -f "$R/etc/resolv.conf"; printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$R/etc/resolv.conf"
mkdir -p "$R/etc/apt/apt.conf.d" "$R/etc/dpkg/dpkg.cfg.d" "$R/run/hatch" "$R/tmp" "$R/root" "$R/usr/local/bin" "$R/home/hatch"
printf 'APT::Sandbox::User "root";\nAcquire::Retries "3";\nAPT::Install-Recommends "false";\nAcquire::Languages "none";\n' > "$R/etc/apt/apt.conf.d/99hatch"
printf 'force-unsafe-io\npath-exclude=/usr/share/doc/*\npath-exclude=/usr/share/man/*\n' > "$R/etc/dpkg/dpkg.cfg.d/01-hatch"
cp app/src/main/assets/guest-bin/* "$R/usr/local/bin/"; chmod +x "$R"/usr/local/bin/hatch-*
grep -q '^hatch:' "$R/etc/passwd" || { echo 'hatch:x:1000:1000:Hatch:/home/hatch:/bin/bash' >> "$R/etc/passwd"; echo 'hatch:x:1000:' >> "$R/etc/group"; }
G=(bash "$ROOT/scripts/guest-run.sh")
g() { "${G[@]}" bash -c "$1"; }

FAILS=()
check() { local what="$1"; shift; if "$@"; then echo "  ✓ $what"; else echo "  ✗ $what"; FAILS+=("$what"); fi; }
ARCHIVE="$PROOF/base-before.txt"
g "dpkg-query -W -f='\${db:Status-Abbrev} \${Package}\n' | awk '\$1==\"ii\"{print \$2}' | sort -u" > "$ARCHIVE"
g "apt-mark showauto | sed 's/:[a-z0-9]*\$//' | sort -u" > "$PROOF/auto-before.txt"
stamp "base: $(wc -l < "$ARCHIVE") packages"
# Base intact: apt check passes, dpkg has nothing half-done, apt/util-linux/libudev1/libapt-pkg are there,
# and every base package is still installed.
base_intact() {
  local tag="$1"
  g "dpkg-query -W -f='\${db:Status-Abbrev} \${Package}\n' | awk '\$1==\"ii\"{print \$2}' | sort -u" > "$PROOF/inst-$tag.txt"
  check "[$tag] apt-get check" g 'apt-get check >/dev/null 2>&1'
  check "[$tag] dpkg --audit clean" g '[ -z "$(dpkg --audit)" ]'
  check "[$tag] apt, util-linux, libudev1, libapt-pkg7.0, dpkg installed" g "[ \$(dpkg-query -W -f='\${db:Status-Abbrev}\n' apt util-linux libudev1 libapt-pkg7.0 dpkg 2>/dev/null | grep -c '^ii') = 5 ]"
  check "[$tag] apt and mount still run" g 'apt-get --version >/dev/null && mount --version >/dev/null'
  local missing; missing=$(comm -23 "$ARCHIVE" "$PROOF/inst-$tag.txt" | tr '\n' ' ')
  check "[$tag] every base package still installed${missing:+ (missing: $missing)}" test -z "$missing"
}
install() { # app [NO_RECORD]
  stamp "install $1${2:+ (like Hatch 0.2.0: no record)}"
  local cmd; cmd=$(NO_RECORD="${2:-0}" python3 scripts/catalogue-args.py install "$1")
  check "install $1" g "$cmd > /tmp/install-$1.log 2>&1 || { tail -30 /tmp/install-$1.log; exit 1; }"
  cp "$R/tmp/install-$1.log" "$PROOF/" 2>/dev/null || true
}
uninstall() { # app expect-rc keep-apps...
  local app="$1" want="$2"; shift 2
  stamp "uninstall $app (others installed: ${*:-none})"
  local cmd; cmd=$(KEEP_APPS="$*" python3 scripts/catalogue-args.py uninstall "$app")
  set +e; g "$cmd" > "$PROOF/uninstall-$app.log" 2>&1; local rc=$?; set -e
  grep -E '^hatch:|^E:' "$PROOF/uninstall-$app.log" | cut -c1-240
  check "uninstall $app exit $want (got $rc)" test "$rc" = "$want"
}
works() { # app
  case "$1" in
    blender) check "blender still runs (--version)" g 'blender --version 2>/dev/null | grep -q "^Blender"' ;;
    code) check "VS Code: every library resolves" g '! ldd /usr/share/code/code | grep -q "not found"' ;;
    steam) check "Steam's libraries are installed" g 'dpkg-query -W libnss3 libgtk-3-0t64 libudev1 >/dev/null' ;;
  esac
}
fake_steam_data() { g 'mkdir -p /home/hatch/.local/share/Steam/steamrtarm64 /home/hatch/.steam && echo x > /home/hatch/.local/share/Steam/steamrtarm64/steam && ln -sfn /home/hatch/.local/share/Steam /home/hatch/.steam/root'; }

# ---- 1. install Blender + VS Code (shared deps), then Steam; the record holds only what Steam added ----
for a in $APPS2; do install "$a"; done
install steam
g 'cat /var/lib/hatch/apps/steam.pkgs' > "$PROOF/steam-record.txt"
stamp "steam record: $(wc -l < "$PROOF/steam-record.txt") packages"
check "Steam's record has no base / pre-existing package" test -z "$(comm -12 "$PROOF/steam-record.txt" "$ARCHIVE")"
check "Steam's record has no libudev1 / libapt-pkg7.0 / util-linux / apt" bash -c "! grep -qxE 'libudev1|libapt-pkg7.0|util-linux|apt|libdbus-1-3' '$PROOF/steam-record.txt'"
check "libudev1 is still marked automatic (install put the mark back)" g 'apt-mark showauto | grep -qx libudev1'
fake_steam_data
for a in $APPS2; do works "$a"; done

# ---- 2. uninstall Steam while Blender + VS Code stay ----
uninstall steam 0 $APPS2
base_intact after-steam
for a in $APPS2; do works "$a"; done
check "Steam's data and client dir are gone" g '[ ! -e /home/hatch/.local/share/Steam ] && [ ! -e /home/hatch/.steam ]'
check "pciutils (Steam only) removed" g '! dpkg-query -W -f="\${db:Status-Abbrev}" pciutils 2>/dev/null | grep -q ^ii'
g 'apt-get -s autoremove' | awk '/^Remv /{print $2}' > "$PROOF/orphans-after-steam.txt"
check "nothing Steam added is left orphaned" bash -c "! grep -qxFf '$PROOF/steam-record.txt' '$PROOF/orphans-after-steam.txt'"
check "the install record is gone" g '[ ! -e /var/lib/hatch/apps/steam.pkgs ]'

# ---- 3. Bently's phone: Steam installed by Hatch 0.2.0 (no record; libudev1 etc. now marked manual) ----
install steam 1
g 'apt-mark manual libudev1 libdbus-1-3 curl >/dev/null'
fake_steam_data
# The exact 0.2.0 command: Uninstall.aptTargets = catalogue packages minus its 36-name BasePackages list
OLD=$(python3 - <<'PY'
import json, re
base = {l.strip() for l in open("scripts/fixtures/uninstall-0.2.0-base.txt") if l.strip() and not l.startswith("#")}
steam = next(a for a in json.load(open("catalogue/catalogue.json"))["apps"] if a["id"] == "steam")
print(" ".join(p for p in steam["packages"] if p not in base))
PY
)
stamp "reproduce 0.2.0: apt-get purge -y --autoremove <Steam's catalogue packages minus the 36 'base' names>"
set +e; g "DEBIAN_FRONTEND=noninteractive apt-get -s purge -y --autoremove $OLD" > "$PROOF/repro-v0.2.0.log" 2>&1; rc=$?; set -e
grep -E 'libudev1|libapt-pkg|^E:' "$PROOF/repro-v0.2.0.log" | head -6
check "0.2.0's uninstall command still fails here (bug reproduced; simulated, nothing removed)" test "$rc" != 0
uninstall steam 0 $APPS2
base_intact after-legacy-steam
for a in $APPS2; do works "$a"; done
check "Steam's data gone (legacy path)" g '[ ! -e /home/hatch/.local/share/Steam ]'

# ---- 4. a damaged rootfs: an older uninstall removed base packages + an interrupted dpkg run ----
stamp "damage: base packages purged (wmctrl, xterm), one force-removed under its dependants (libxcb-present0), a package left unpacked-but-unconfigured"
g 'apt-get purge -y -q wmctrl xterm >/tmp/damage.log 2>&1; tail -1 /tmp/damage.log; dpkg --remove --force-depends libxcb-present0 >/dev/null 2>&1; cd /tmp && apt-get download -q hello >/dev/null 2>&1 && dpkg --unpack hello_*.deb >/dev/null 2>&1; dpkg --audit | head -3; apt-get check 2>&1 | tail -2' || true
check "damage is visible (wmctrl gone, apt-get check fails)" g '! dpkg-query -W -f="\${db:Status-Abbrev}" wmctrl 2>/dev/null | grep -q ^ii && ! apt-get check >/dev/null 2>&1'
stamp "hatch-uninstall-app --repair (what the store runs on start)"
check "repair succeeds" g 'hatch-uninstall-app --repair > /tmp/repair.log 2>&1; rc=$?; grep "^hatch:" /tmp/repair.log; exit $rc'
cp "$R/tmp/repair.log" "$PROOF/" 2>/dev/null || true
base_intact after-repair
check "repair is a no-op when healthy" g '! hatch-uninstall-app --repair | grep -q repairing'

# ---- 5. apt fails mid-uninstall → app files still go, exit 10 (soft warning), base intact ----
install steam
fake_steam_data
g 'printf "#!/bin/sh\necho prerm-fail >&2; exit 1\n" > /var/lib/dpkg/info/pciutils.prerm; chmod +x /var/lib/dpkg/info/pciutils.prerm'
uninstall steam 10 $APPS2
check "soft warning reported" grep -q 'uninstall finished with warnings' "$PROOF/uninstall-steam.log"
check "Steam's data removed even though apt failed" g '[ ! -e /home/hatch/.local/share/Steam ]'
base_intact after-apt-failure
check "what apt couldn't remove is remembered" g 'grep -qx pciutils /var/lib/hatch/apps/leftovers'
g 'rm -f /var/lib/dpkg/info/pciutils.prerm'
stamp "next store start: --repair tidies the leftovers up"
g 'hatch-uninstall-app --repair' | grep '^hatch:' || true
check "leftovers tidied up (pciutils gone, list empty)" g '! dpkg-query -W -f="\${db:Status-Abbrev}" pciutils 2>/dev/null | grep -q ^ii && [ ! -s /var/lib/hatch/apps/leftovers ]'

# ---- 6. reinstall works; then uninstall everything; the base system is exactly back ----
install steam
works steam
for a in $APPS2; do install "$a"; works "$a"; done
uninstall steam 0 $APPS2
others=($APPS2)
for i in "${!others[@]}"; do uninstall "${others[$i]}" 0 "${others[@]:$((i+1))}"; done
base_intact after-all
g "dpkg-query -W -f='\${db:Status-Abbrev} \${Package}\n' | awk '\$1==\"ii\"{print \$2}' | sort -u" > "$PROOF/inst-final.txt"
# Left behind may only be what uninstall must never remove (Essential / Priority required|important, e.g. systemd
# that VS Code pulled in) and what those need; nothing orphaned.
comm -13 "$ARCHIVE" "$PROOF/inst-final.txt" | grep -vx hello > "$PROOF/left-final.txt" || true
g "apt-get -s autoremove | awk '/^Remv /{print \$2}' | sed 's/:.*//' | xargs -r dpkg-query -W -f='\${Package} \${Essential} \${Priority}\n'" \
  | awk '!($2=="yes"||$3=="required"||$3=="important"){print $1}' > "$PROOF/orphans-final.txt"   # protected ones never go
echo "  left installed (protected or needed by those): $(tr '\n' ' ' < "$PROOF/left-final.txt")"
check "nothing the apps added is left orphaned ($(comm -12 <(sort "$PROOF/left-final.txt") <(sort "$PROOF/orphans-final.txt") | tr '\n' ' '))" \
  test -z "$(comm -12 <(sort "$PROOF/left-final.txt") <(sort "$PROOF/orphans-final.txt"))"
prio=$(g "dpkg-query -W -f='\${Package} \${Essential} \${Priority}\n' $(tr '\n' ' ' < "$PROOF/left-final.txt") 2>/dev/null" | awk '$2=="yes"||$3=="required"||$3=="important"{print $1}' | tr '\n' ' ')
check "anything left is anchored by a protected package (${prio:-none})" bash -c "[ ! -s '$PROOF/left-final.txt' ] || [ -n '$prio' ]"
install steam
works steam
base_intact reinstall

stamp "done; failed: ${FAILS[*]:-none}"
[ ${#FAILS[@]} -eq 0 ]
