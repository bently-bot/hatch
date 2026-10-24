#!/usr/bin/env bash
# End-to-end guest proof: real Debian 13 rootfs under proot (same flags as the app), apps installed with the
# app's own hatch-install-app from catalogue.json, launched with hatch-launch, rendered by Hatch's compositor.
# Env: PROOF_DIR, HATCH_ROOTFS (reuse existing), PROOT, APPS, SOFT_APPS, SKIP_HOST_DEPS=1
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
PROOF="${PROOF_DIR:-$ROOT/harness-out}"; mkdir -p "$PROOF" build/host
WORK="${RUNNER_TEMP:-/tmp/hatch-ci}"; mkdir -p "$WORK"
export HATCH_ROOTFS="${HATCH_ROOTFS:-$WORK/rootfs}"
APPS="${APPS:-mousepad galculator gnome-mines notepadpp xterm}"
SOFT_APPS="${SOFT_APPS:-}"
t0=$(date +%s); stamp() { echo "[$(( $(date +%s) - t0 ))s] $*"; }

if [ "${SKIP_HOST_DEPS:-0}" != 1 ]; then
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gcc make libtalloc-dev curl xz-utils python3-pil unzip
  curl -fsSL -o /tmp/kotlin.zip https://github.com/JetBrains/kotlin/releases/download/v2.1.20/kotlin-compiler-2.1.20.zip
  sudo unzip -qo /tmp/kotlin.zip -d /opt
  export PATH="/opt/kotlinc/bin:$PATH"
fi
# Termux's proot fork (same code as the app's libproot.so; Ubuntu's arm64 proot 5.1.0 segfaults on 6.x kernels)
if [ -z "${PROOT:-}" ]; then
  PROOT_SHA=b27d81a68d765f3462e807f4a14e01a03f8e89ea
  if [ ! -x "$WORK/proot-src/src/proot" ]; then
    stamp "build termux/proot $PROOT_SHA"
    rm -rf "$WORK/proot-src"; git init -q "$WORK/proot-src"
    git -C "$WORK/proot-src" fetch -q --depth 1 https://github.com/termux/proot "$PROOT_SHA"
    git -C "$WORK/proot-src" checkout -q FETCH_HEAD
    # No 32-bit guest loader on CI (arm64 runner has no -m32 / armhf cross compiler)
    make -C "$WORK/proot-src/src" -j"$(nproc)" HAS_LOADER_32BIT= proot >/dev/null 2>&1 || make -C "$WORK/proot-src/src" HAS_LOADER_32BIT= proot
  fi
  export PROOT="$WORK/proot-src/src/proot"
fi
export HATCH_L2S="${HATCH_L2S:-1}"   # termux proot honours PROOT_L2S_DIR, like on the phone
KLIB="$(dirname "$(dirname "$(readlink -f "$(command -v kotlinc)")")")/lib"
stamp "build compositor harness"
gcc -shared -fPIC -O2 -DHATCH_HOST -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -o build/host/libhatchnative.so app/src/main/cpp/hatchnative.c -lpthread
kotlinc -d build/host/classes -jvm-target 17 app/src/main/kotlin/dev/hatch/wayland/*.kt harness/src/dev/hatch/harness/Main.kt 2>&1 | grep -v '^warning' || true
CP="build/host/classes:$KLIB/kotlin-stdlib.jar"; NAT="$ROOT/build/host/libhatchnative.so"

# ---- rootfs (same source the app downloads on arm64) ----
if [ ! -f "$HATCH_ROOTFS/etc/os-release" ]; then
  RF=app/src/main/kotlin/dev/hatch/guest/RootfsCatalog.kt
  URL=$(grep -A1 'ROOTFS_URL' "$RF" | grep -o 'https://[^"]*'); SHA=$(grep -A1 'ROOTFS_SHA256 =' "$RF" | grep -o '[0-9a-f]\{64\}')
  if [ "$(uname -m)" != aarch64 ]; then URL=${URL/arm64/amd64}; SHA=""; fi
  stamp "download rootfs $URL"
  if [ -s "$WORK/rootfs.tar.xz" ]; then stamp "cached rootfs"; elif ! curl -fL --retry 3 -o "$WORK/rootfs.tar.xz" "$URL"; then
    # linuxcontainers prunes old builds; fall back to the newest build of the same image
    base=${URL%/*/rootfs.tar.xz}; latest=$(curl -fsSL "$base/" | grep -o '[0-9]\{8\}_[0-9][0-9]:[0-9][0-9]' | sort | tail -1)
    stamp "pinned build gone; using $latest (sha not checked)"; SHA=""
    curl -fL --retry 3 -o "$WORK/rootfs.tar.xz" "$base/$latest/rootfs.tar.xz"
  fi
  [ -z "$SHA" ] || echo "$SHA  $WORK/rootfs.tar.xz" | sha256sum -c -
  mkdir -p "$HATCH_ROOTFS"
  tar -xJf "$WORK/rootfs.tar.xz" -C "$HATCH_ROOTFS" --no-same-owner --exclude='dev/*' 2>/dev/null || true
fi
# Mirror GuestConfig.apply
R="$HATCH_ROOTFS"
chmod -R u+w "$R/etc" 2>/dev/null || true
rm -f "$R/etc/resolv.conf"; printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$R/etc/resolv.conf"
printf '127.0.0.1 localhost\n::1 localhost\n' > "$R/etc/hosts"
mkdir -p "$R/etc/apt/apt.conf.d" "$R/etc/dpkg/dpkg.cfg.d" "$R/run/hatch" "$R/tmp" "$R/root" "$R/usr/local/bin"
printf 'APT::Sandbox::User "root";\nAcquire::Retries "3";\nAPT::Install-Recommends "false";\nAcquire::Languages "none";\n' > "$R/etc/apt/apt.conf.d/99hatch"
printf 'force-unsafe-io\npath-exclude=/usr/share/doc/*\npath-exclude=/usr/share/man/*\n' > "$R/etc/dpkg/dpkg.cfg.d/01-hatch"
cp app/src/main/assets/guest-bin/* "$R/usr/local/bin/"; chmod +x "$R"/usr/local/bin/hatch-*
# Mirror GuestScripts.ensureUser: apps run as "hatch" (uid 1000), installs as fake root
grep -q '^hatch:' "$R/etc/passwd" || {
  chmod u+w "$R/etc/passwd" "$R/etc/group" "$R/etc/shadow" 2>/dev/null || true
  echo 'hatch:x:1000:1000:Hatch:/home/hatch:/bin/bash' >> "$R/etc/passwd"; echo 'hatch:x:1000:' >> "$R/etc/group"
  echo 'hatch:!:20000:0:99999:7:::' >> "$R/etc/shadow"; }
mkdir -p "$R/home/hatch"
G=(bash "$ROOT/scripts/guest-run.sh")
GU=(env HATCH_AS_USER=1 bash "$ROOT/scripts/guest-run.sh")
stamp "guest: $("${G[@]}" sh -c '. /etc/os-release; echo $PRETTY_NAME; uname -m')"

png_ok() {
  python3 - "$1" "${2:-12}" <<'PY'
import sys
from PIL import Image
p, minu = sys.argv[1], int(sys.argv[2])
im = Image.open(p).convert("RGB"); w, h = im.size
px = list(im.getdata()) if not hasattr(im, 'get_flattened_data') else list(im.get_flattened_data()); step = max(1, (w*h)//6000)
s = [px[i] for i in range(0, w*h, step)]
uniq = len(set(s)); bg = sum(1 for c in s if c == (42, 42, 42))
print(f"png {p} {w}x{h} uniq={uniq} content={100-100*bg//len(s)}%")
sys.exit(0 if uniq >= minu else 1)
PY
}
capture() { # name seconds harness-opts... -- cmd...
  local name="$1" secs="$2"; shift 2
  local opts=(); while [ "$1" != "--" ]; do opts+=("$1"); shift; done; shift
  rm -f "$PROOF/$name.png" "$PROOF/client.log"
  set +e
  java -Dhatch.native="$NAT" -cp "$CP" dev.hatch.harness.MainKt "$WORK/wl-$name" "$PROOF/$name.png" "$secs" "${opts[@]}" -- "$@" \
    > "$PROOF/$name-host.log" 2>&1
  local rc=$?; set -e
  mv -f "$PROOF/client.log" "$PROOF/$name-client.log" 2>/dev/null || true
  grep -E 'windows:|wrote|timed out|exited' "$PROOF/$name-host.log" | tail -8 || true
  [ $rc -eq 0 ] || { echo "--- $name client log ---"; tail -30 "$PROOF/$name-client.log" 2>/dev/null || true; }
  return $rc
}
title_for() { case "$1" in notepadpp) echo "Notepad++";; mousepad) echo "Mousepad";; galculator) echo "alculator";; gnome-mines) echo "Mines";; xterm) echo "Xwayland";; *) echo "";; esac; }

FAILED=()
for app in $APPS; do
  stamp "== install $app =="
  if ! "${G[@]}" bash -c "$(python3 scripts/catalogue-args.py install "$app") > /tmp/hatch-install-$app.log 2>&1; rc=\$?; tail -5 /tmp/hatch-install-$app.log; exit \$rc"; then
    echo "install $app FAILED"; FAILED+=("$app"); continue
  fi
  stamp "== launch $app =="
  LAUNCH=$(python3 scripts/catalogue-args.py launch "$app")
  T=$(title_for "$app"); topt=(); [ -n "$T" ] && topt=(--title="$T")
  secs=90; [ "$app" = notepadpp ] && secs=420
  minu=12; [ "$app" = xterm ] && minu=2   # xterm's core bitmap font really is 2 colours
  if capture "$app" "$secs" "${topt[@]}" --settle=3000 -- "${GU[@]}" bash -c "$LAUNCH" && png_ok "$PROOF/$app.png" "$minu"; then
    stamp "$app OK"
  else
    FAILED+=("$app")
  fi
done

if [[ " $APPS " == *" mousepad "* ]]; then
  stamp "== mousepad input inject =="
  if capture mousepad-inject 90 --title=Mousepad --inject -- "${GU[@]}" bash -c "$(python3 scripts/catalogue-args.py launch mousepad)" && png_ok "$PROOF/mousepad-inject.png"; then
    stamp "inject OK"; else FAILED+=(mousepad-inject); fi
fi

if [[ " $APPS " == *" mousepad "* ]]; then
  stamp "== mousepad menu (xdg_popup) + hover + dismiss + typing =="
  rm -f "$PROOF"/mousepad-menu*.png
  if JAVA_TOOL_OPTIONS="-Dhatch.inject=script -Dhatch.script=s:before;c:19,48;w:1500;s:open;m:40,103;w:800;s:hover;c:900,600;w:1000;s:dismissed;c:640,400;k:35,23;w:1000" \
     capture mousepad-menu 90 --title=Mousepad --settle=2000 --inject -- "${GU[@]}" bash -c "$(python3 scripts/catalogue-args.py launch mousepad)" \
     && python3 - "$PROOF" <<'PY'
import sys
from PIL import Image, ImageChops
d = sys.argv[1]
im = {k: Image.open(f"{d}/mousepad-menu-{k}.png").convert("RGB") for k in ("before", "open", "hover", "dismissed")}
im["final"] = Image.open(f"{d}/mousepad-menu.png").convert("RGB")
def diff(a, b): return ImageChops.difference(im[a], im[b]).getbbox()
checks = {"menu opened": diff("before", "open"), "hover highlight": diff("open", "hover"),
          "dismissed": diff("hover", "dismissed"), "typed": diff("dismissed", "final")}
for k, v in checks.items(): print(f"{k}: {v}")
sys.exit(0 if all(checks.values()) else 1)
PY
  then stamp "menu OK"; else FAILED+=(mousepad-menu); fi
fi

if [[ " $APPS " == *" mousepad "* ]]; then
  stamp "== clipboard: guest copy → host, host (Android) → guest paste, round-trip =="
  if JAVA_TOOL_OPTIONS="-Dhatch.inject=script -Dhatch.script=c:640,400;t:CopyMe123;kc:29+30;kc:29+46;w:1500;g:after-copy;p:FromAndroid;c:640,400;kc:29+30;kc:29+47;w:1500;s:pasted;kc:29+30;kc:29+46;w:1500;g:roundtrip" \
     capture clipboard 90 --title=Mousepad --settle=2000 --inject -- "${GU[@]}" bash -c "$(python3 scripts/catalogue-args.py launch mousepad)" \
     && grep -q 'clipboard\[after-copy\]: CopyMe123' "$PROOF/clipboard-host.log" && grep -q 'clipboard\[roundtrip\]: FromAndroid' "$PROOF/clipboard-host.log"; then
    grep 'clipboard\[' "$PROOF/clipboard-host.log"; stamp "clipboard OK"
  else grep 'clipboard\[' "$PROOF/clipboard-host.log" || true; FAILED+=(clipboard); fi
fi

stamp "done; failed: ${FAILED[*]:-none}"
hard=()
for f in "${FAILED[@]:-}"; do [ -n "$f" ] && [[ " $SOFT_APPS " != *" $f "* ]] && hard+=("$f"); done
[ ${#hard[@]} -eq 0 ] || { echo "REQUIRED FAILURES: ${hard[*]}"; exit 1; }
