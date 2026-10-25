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
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gcc make libtalloc-dev curl xz-utils zstd python3-pil unzip
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
  -o build/host/libhatchnative.so app/src/main/cpp/hatchnative.c -lpthread -lm
kotlinc -d build/host/classes -jvm-target 17 app/src/main/kotlin/dev/hatch/wayland/*.kt harness/src/dev/hatch/harness/Main.kt > "$WORK/kotlinc.log" 2>&1 \
  || { grep -v '^warning' "$WORK/kotlinc.log"; echo "FATAL: compositor harness does not compile (wayland/*.kt must stay Android-free)"; exit 1; }
CP="build/host/classes:$KLIB/kotlin-stdlib.jar"; NAT="$ROOT/build/host/libhatchnative.so"

# ---- rootfs: the prebuilt image the app streams on first run (RootfsCatalog.PREBAKED); LEGACY_ROOTFS=1 → old source ----
ARCH=$([ "$(uname -m)" = aarch64 ] && echo arm64 || echo amd64)
PRE=$(python3 - "$ARCH" <<'PY'
import re,sys
s=open("app/src/main/kotlin/dev/hatch/guest/RootfsCatalog.kt").read()
m=re.search(r'"%s" to Prebaked\(\s*"([^"]+)",\s*"([0-9a-f]{64})"' % sys.argv[1], s)
print(f"{m.group(1)} {m.group(2)}" if m else "")
PY
)
if [ ! -f "$HATCH_ROOTFS/etc/os-release" ] && [ -n "$PRE" ] && [ "${LEGACY_ROOTFS:-0}" != 1 ]; then
  read PURL PSHA <<<"$PRE"
  stamp "prebuilt rootfs $PURL"
  [ -s "$WORK/rootfs.tar.zst" ] && echo "$PSHA  $WORK/rootfs.tar.zst" | sha256sum -c - >/dev/null 2>&1 && stamp "cached prebuilt rootfs" \
    || curl -fL --retry 3 -o "$WORK/rootfs.tar.zst" "$PURL"
  echo "$PSHA  $WORK/rootfs.tar.zst" | sha256sum -c -
  mkdir -p "$HATCH_ROOTFS"
  zstd -dc "$WORK/rootfs.tar.zst" | tar -x -C "$HATCH_ROOTFS" --no-same-owner --exclude='dev/*' 2>/dev/null || true
  stamp "prebuilt rootfs unpacked ($(du -sh "$HATCH_ROOTFS" | cut -f1))"
fi
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
sh scripts/build-guest-libs.sh "$WORK/guest-lib" && mkdir -p "$R/usr/local/lib/hatch" && cp "$WORK/guest-lib/arm64-v8a/"*.so "$R/usr/local/lib/hatch/" || echo "WARN: guest libs not built"
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
title_for() { case "$1" in notepadpp) echo "Notepad++";; mousepad) echo "Mousepad";; galculator) echo "alculator";; gnome-mines) echo "Mines";; xterm|blender|steam) echo "Xwayland";; cursor) echo "Cursor";; hdrview) echo "HDRView";; brave-browser) echo "Brave";; org.darktable.Darktable) echo "darktable";; hdrview) echo "HDRView";; *) echo "";; esac; }
if [ "${SWEEP:-0}" = 1 ]; then . "$ROOT/scripts/app-sweep.sh"; fi

FAILED=()
for app in $APPS; do
  stamp "== install $app =="
  if ! "${G[@]}" bash -c "$(python3 scripts/catalogue-args.py install "$app") > /tmp/hatch-install-$app.log 2>&1; rc=\$?; tail -5 /tmp/hatch-install-$app.log; [ \$rc = 0 ] || grep -E \"^(E:|dpkg: |pmerror)|rror|hatch:\" /tmp/hatch-install-$app.log | head -25; exit \$rc"; then
    echo "install $app FAILED"; FAILED+=("$app"); continue
  fi
  if [ "$app" = steam ]; then   # Experimental: fetch Valve's ARM64 client (~300 MB) and list libs it can't resolve
    stamp "steam: fetch + diag"
    "${GU[@]}" bash -c 'hatch-steam --diag' > "$PROOF/steam-diag.log" 2>&1 || echo "steam diag rc=$?"
    tail -40 "$PROOF/steam-diag.log"
    "${G[@]}" bash -c 'du -sh /home/hatch/.local/share/Steam 2>/dev/null; ls /home/hatch/.local/share/Steam/steamrtarm64 | head -80 | tr "\n" " "' || true
  fi
  stamp "== launch $app =="
  LAUNCH=$(python3 scripts/catalogue-args.py launch "$app")
  T=$(title_for "$app"); topt=(); [ -n "$T" ] && topt=(--title="$T")
  secs=90; [ "$app" = notepadpp ] && secs=420; [ "$app" = blender ] && secs=300; case "$app" in cursor|brave-browser) secs=240 ;; org.darktable.*) secs=420 ;; steam) secs=600 ;; esac
  settle=3000; [ "$app" = blender ] && settle=90000; case "$app" in cursor|brave-browser) settle=30000 ;; org.darktable.*) settle=90000 ;; steam) settle=180000 ;; esac   # rootful Xwayland appears first; Blender compiles GL shaders on llvmpipe
  minu=12; [ "$app" = xterm ] && minu=2   # xterm's core bitmap font really is 2 colours
  # Flathub apps run from their spec on the host side (own root), not inside the Debian guest's shell.
  case "$LAUNCH" in hatch-flatpak-run*) RUNARGS=(${LAUNCH}) ;; *) RUNARGS=(bash -c "$LAUNCH") ;; esac
  # DEBUG_APPS: trace the Wayland protocol of these apps (client side) into <app>-client.log
  [[ " ${DEBUG_APPS:-} " == *" $app "* ]] && RUNARGS=(env WAYLAND_DEBUG=client "${RUNARGS[@]}")
  if [ "$app" = steam ]; then "${GU[@]}" bash -c 'tail -60 ~/.local/share/Steam/logs/bootstrap_log.txt ~/.local/share/Steam/logs/stderr.txt 2>/dev/null' > "$PROOF/steam-logs-pre.log" 2>&1 || true; fi
  if capture "$app" "$secs" "${topt[@]}" --settle=$settle -- "${GU[@]}" "${RUNARGS[@]}" && png_ok "$PROOF/$app.png" "$minu"; then
    stamp "$app OK"
  else
    FAILED+=("$app")
  fi
  # Runner disk is small: drop downloaded .debs between apps (Electron apps are ~150 MB each).
  "${G[@]}" bash -c 'apt-get clean >/dev/null 2>&1; rm -rf /var/cache/apt/archives/partial/* /tmp/hatch-dl-* 2>/dev/null; true' || true
  df -h / | tail -1 | awk '{print "disk free: "$4}'
done

if [ "${GPU_PROXY:-0}" = 1 ]; then
  # Same GL stack the app uses on Adreno (Gpu.env(true): Zink → Vulkan → MESA_VK_WSI_DEBUG=sw present into Hatch),
  # with lavapipe standing in for Turnip/KGSL, which only exists on Qualcomm hardware.
  stamp "== GPU path proxy: GL → Zink → Vulkan (lavapipe for Turnip) → sw WSI → Hatch compositor =="
  "${G[@]}" bash -c 'DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends mesa-vulkan-drivers mesa-utils libvulkan1 >/tmp/gpu-apt.log 2>&1; tail -1 /tmp/gpu-apt.log; ls /usr/share/vulkan/icd.d/'
  LVP=$("${G[@]}" bash -c 'ls /usr/share/vulkan/icd.d/lvp_icd*.json | head -1' | tr -d '\r')
  ZENV="LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=zink MESA_LOADER_DRIVER_OVERRIDE=zink VK_ICD_FILENAMES=$LVP VK_DRIVER_FILES=$LVP MESA_VK_WSI_DEBUG=sw LIBGL_KOPPER_DRI2=1 D3D_ALWAYS_SOFTWARE=1 HATCH_GPU=1"  # D3D_ALWAYS_SOFTWARE: proxy only, lets Zink accept the CPU Vulkan device
  SENV="LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe MESA_LOADER_DRIVER_OVERRIDE=llvmpipe"
  cat > "$HATCH_ROOTFS/tmp/hatch-fps.py" <<'PY'
import bpy, time
def bench():
    w = bpy.context.window_manager.windows[0]
    area = next(a for a in w.screen.areas if a.type == "VIEW_3D")
    with bpy.context.temp_override(window=w, area=area):
        bpy.ops.wm.redraw_timer(type="DRAW_WIN_SWAP", iterations=3)
        t = time.time(); n = 30
        bpy.ops.wm.redraw_timer(type="DRAW_WIN_SWAP", iterations=n)
        dt = time.time() - t
    import gpu
    print(f"HATCH-FPS renderer={gpu.platform.renderer_get()} viewport_fps={n/dt:.1f} ms_per_frame={1000*dt/n:.1f}", flush=True)
bpy.app.timers.register(bench, first_interval=25)
PY
  for v in llvmpipe zink; do
    E=$SENV; [ $v = zink ] && E=$ZENV
    if capture "gpu-glxgears-$v" 60 --title=Xwayland --settle=15000 -- "${GU[@]}" bash -c "env $E glxinfo -B 2>&1 | grep -E 'renderer|version string'; env $E hatch-x11 timeout 40 glxgears -info 2>&1 | grep -E 'GL_RENDERER|frames in' "; then :; fi
    grep -E 'renderer string|GL_RENDERER|frames in' "$PROOF/gpu-glxgears-$v-client.log" | tail -4 | sed "s/^/  [$v] /" || true
    if capture "gpu-blender-$v" 300 --title=Xwayland --settle=60000 -- "${GU[@]}" bash -c "env $E hatch-x11 blender --python /tmp/hatch-fps.py"; then :; fi
    grep -h 'HATCH-FPS' "$PROOF/gpu-blender-$v-client.log" | sed "s/^/  [$v] /" || stamp "WARN: no Blender FPS line for $v"
  done
  grep -qE 'GL_RENDERER *= *zink|renderer string: zink' "$PROOF/gpu-glxgears-zink-client.log" \
    && grep -q 'HATCH-FPS renderer=zink' "$PROOF/gpu-blender-zink-client.log" && stamp "GPU proxy OK: glxgears and Blender run on Zink over Vulkan, presented through Hatch" \
    || { stamp "GPU proxy FAILED: Zink not active"; FAILED+=(gpu-proxy-zink); }
fi

if [[ " $APPS " == *" doom "* ]]; then
  # DOOM: title → Enter ×4 (menu, New Game, episode 1, "Hurt me plenty") → E1M1; walk forward, turn, fire; the
  # gameplay frame must not be black. Also reports which IWAD crispy-doom picked (shareware doom1.wad or Freedoom).
  stamp "== doom gameplay =="
  grep -hE 'doom1.wad|optional download' /tmp/hatch-install-doom.log 2>/dev/null | sed 's/^/  install: /' || true
  "${G[@]}" bash -c 'ls -la /opt/hatch/doom /usr/share/games/doom 2>/dev/null; sha256sum /opt/hatch/doom/*.wad 2>/dev/null' | sed 's/^/  /' || true
  if JAVA_TOOL_OPTIONS="-Dhatch.inject=script -Dhatch.script=w:3000;s:title;k:28;w:700;s:menu;k:28;w:700;k:28;w:700;k:28;w:2500;s:e1m1;kd:103;w:1200;ku:103;kd:106;w:500;ku:106;kd:29;w:400;ku:29;w:300;kd:103;w:800;ku:103;w:500;s:gameplay" \
     capture doom-play 120 --settle=4000 --inject -- "${GU[@]}" bash -c "$(python3 scripts/catalogue-args.py launch doom)" \
     && png_ok "$PROOF/doom-play-gameplay.png" 12 && png_ok "$PROOF/doom-play-e1m1.png" 12; then
    stamp "doom gameplay OK"
  else FAILED+=(doom-gameplay); fi
  grep -hiE 'iwad|W_Init|doom1.wad|freedoom|Game mode' "$PROOF/doom-play-client.log" 2>/dev/null | head -8 | sed 's/^/  doom: /' || true
fi

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

stamp "== audio: guest app → shared PulseAudio → raw PCM on 127.0.0.1:47140 (what AudioBridge plays) =="
python3 - "$R/tmp/hatch-sine.wav" <<'PY'
import sys, wave, math, struct
w = wave.open(sys.argv[1], "wb"); w.setnchannels(2); w.setsampwidth(2); w.setframerate(48000)
w.writeframes(b"".join(struct.pack("<hh", v, v) for v in (int(12000 * math.sin(2 * math.pi * 440 * i / 48000)) for i in range(96000))))
PY
mkdir -p "$WORK/xdg-audio" "$WORK/xdg-other"
XDG_RUNTIME_DIR="$WORK/xdg-audio" timeout 60 "${GU[@]}" /usr/local/bin/hatch-audio > "$PROOF/audio-server.log" 2>&1 &
sleep 5
python3 - > "$PROOF/audio-host.log" 2>&1 <<'PY' &
import socket, time, array
s = socket.create_connection(("127.0.0.1", 47140), timeout=20); buf = b""; t = time.time()
while time.time() - t < 8:
    try: d = s.recv(65536)
    except socket.timeout: break
    if not d: break
    buf += d
a = array.array("h"); a.frombytes(buf[:len(buf) // 2 * 2])
print(f"host received {len(buf)} bytes, peak={max(map(abs, a), default=0)}")
PY
sleep 1
XDG_RUNTIME_DIR="$WORK/xdg-other" timeout 30 "${GU[@]}" bash -c '. /usr/local/bin/hatch-audio-env; paplay /tmp/hatch-sine.wav; echo paplay rc=$?' || true
sleep 9
cat "$PROOF/audio-server.log" "$PROOF/audio-host.log"
peak=$(grep -o 'peak=[0-9]*' "$PROOF/audio-host.log" | cut -d= -f2 || true)
if [ "${peak:-0}" -gt 5000 ]; then stamp "audio OK (peak $peak)"; else FAILED+=(audio); fi

# GPU path (Turnip/KGSL): no Adreno on CI, but prove the bundled driver unpacks, its deps install and it links in the
# real guest — the self-test must get as far as the /dev/kgsl-3d0 check (only the Fold 8 can go further).
if [ -n "${TURNIP_TAR:-}" ] && [ -f "$TURNIP_TAR" ]; then
  mkdir -p "$R/opt/hatch" && cp "$TURNIP_TAR" "$R/opt/hatch/turnip.tar.xz"
  "${G[@]}" /usr/local/bin/hatch-gpu-check > "$PROOF/gpu-check.log" 2>&1 || true
  cat "$PROOF/gpu-check.log"
  if grep -q 'kgsl-3d0 not visible' "$R/var/lib/hatch/gpu-check" 2>/dev/null; then stamp "gpu path OK up to device (driver unpacked + linked)"
  else stamp "gpu path FAILED: $(cat "$R/var/lib/hatch/gpu-check" 2>/dev/null)"; FAILED+=(gpu); fi
fi

stamp "done; failed: ${FAILED[*]:-none}"
hard=()
for f in "${FAILED[@]:-}"; do [ -n "$f" ] && [[ " $SOFT_APPS " != *" $f "* ]] && hard+=("$f"); done
[ ${#hard[@]} -eq 0 ] || { echo "REQUIRED FAILURES: ${hard[*]}"; exit 1; }
