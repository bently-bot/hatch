#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PROOF="${PROOF_DIR:-$ROOT/harness-out}"
mkdir -p "$PROOF" build/host
export RUNNER_TEMP="${RUNNER_TEMP:-/tmp/hatch-ci}"
mkdir -p "$RUNNER_TEMP"

sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  gcc make weston curl xz-utils unzip ca-certificates \
  mousepad galculator libgtk-3-0 \
  xwayland xterm python3-pil \
  wine64 wine libwine fonts-dejavu-core || \
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  gcc make weston curl ca-certificates mousepad galculator wine64 xwayland xterm python3-pil

curl -fsSL -o /tmp/kotlin.zip https://github.com/JetBrains/kotlin/releases/download/v2.1.20/kotlin-compiler-2.1.20.zip
sudo unzip -qo /tmp/kotlin.zip -d /opt
export PATH="/opt/kotlinc/bin:$PATH"
gcc -shared -fPIC -O2 -DHATCH_HOST -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -o build/host/libhatchnative.so app/src/main/cpp/hatchnative.c -lpthread
kotlinc -d build/host/classes -jvm-target 17 \
  app/src/main/kotlin/dev/hatch/wayland/*.kt harness/src/dev/hatch/harness/Main.kt
CP="build/host/classes:/opt/kotlinc/lib/kotlin-stdlib.jar"
NAT="$ROOT/build/host/libhatchnative.so"

png_ok() {
  local f="$1" min="${2:-8000}"
  [ -f "$f" ] || return 1
  local sz; sz=$(stat -c%s "$f")
  [ "$sz" -ge "$min" ] || { echo "png too small: $f ($sz < $min)"; return 1; }
  python3 - "$f" <<'PY'
import sys
path = sys.argv[1]
data = open(path, "rb").read()
assert data[:8] == b"\x89PNG\r\n\x1a\n", "not png"
import struct
w, h = struct.unpack(">II", data[16:24])
try:
    from PIL import Image
    im = Image.open(path).convert("RGB")
    step = max(1, (w * h) // 4000)
    sample = list(im.getdata())[::step]
    uniq = len(set(sample))
    if uniq < 8:
        print(f"too uniform uniq={uniq}", file=sys.stderr); sys.exit(1)
    print(f"ok {path} {w}x{h} uniq≈{uniq} size={len(data)}")
except Exception as e:
    if w < 64 or h < 64 or len(data) < 2000:
        print(f"weak png {e}", file=sys.stderr); sys.exit(1)
    print(f"ok-fallback {path} {w}x{h} size={len(data)} ({e})")
PY
}

capture() {
  local name="$1"; shift
  local seconds="$1"; shift
  rm -f "$PROOF/$name.png" "$PROOF/${name}-client.log" "$PROOF/${name}-host.log"
  local inject=()
  if [ "${HATCH_INJECT:-0}" = "1" ]; then inject=(--inject); fi
  set +e
  env -u DISPLAY java -Dhatch.native="$NAT" -cp "$CP" \
    dev.hatch.harness.MainKt \
    "$RUNNER_TEMP/wayland-$name" \
    "$PROOF/$name.png" \
    "$seconds" "${inject[@]}" -- "$@" >"$PROOF/${name}-host.log" 2>&1
  local rc=$?
  set -e
  unset HATCH_INJECT
  echo "==== $name host (tail) ===="; tail -40 "$PROOF/${name}-host.log" || true
  [ -f "$PROOF/client.log" ] && mv "$PROOF/client.log" "$PROOF/${name}-client.log" || true
  echo "==== $name client (tail) ===="; tail -40 "$PROOF/${name}-client.log" 2>/dev/null || true
  [ -f "$PROOF/$name.png" ] && ls -la "$PROOF/$name.png"
  return "$rc"
}

echo "== simple-shm =="
capture simple-shm 15 weston-simple-shm
png_ok "$PROOF/simple-shm.png" 2000

echo "== weston-flower =="
capture flower 20 weston-flower || echo "flower soft-fail"
[ -f "$PROOF/flower.png" ] && png_ok "$PROOF/flower.png" 2000 || true

echo "== mousepad (REQUIRED, with input inject) =="
HATCH_INJECT=1 capture mousepad 45 mousepad
png_ok "$PROOF/mousepad.png" 6000

echo "== galculator (REQUIRED) =="
capture galculator 35 galculator
png_ok "$PROOF/galculator.png" 4000

echo "== xterm via Xwayland (best-effort) =="
if command -v Xwayland >/dev/null && command -v xterm >/dev/null; then
  set +e
  capture xterm 45 bash -lc '
    mkdir -p /tmp/.X11-unix
    rm -f /tmp/.X1-lock
    Xwayland :1 -geometry 1280x800 -noreset -ac >/tmp/hatch-xwayland.log 2>&1 &
    sleep 0.8
    export DISPLAY=:1
    unset GDK_BACKEND
    exec xterm -geometry 100x30
  ' || echo "xterm/Xwayland soft-fail"
  set -e
fi

echo "== notepad++ / wine (best-effort) =="
if command -v wine64 >/dev/null || command -v wine >/dev/null; then
  NPP_DIR="$RUNNER_TEMP/npp"
  mkdir -p "$NPP_DIR"
  curl -fsSL -o /tmp/npp.zip \
    https://github.com/notepad-plus-plus/notepad-plus-plus/releases/download/v8.9.8.1/npp.8.9.8.1.portable.arm64.zip
  unzip -qo /tmp/npp.zip -d "$NPP_DIR"
  export WINEPREFIX="$RUNNER_TEMP/wineprefix"
  WINEBIN=$(command -v wine64 || command -v wine)
  echo "wineboot -i (max 8 min)…"
  set +e
  timeout 480 env -u DISPLAY WINEDEBUG=-all WINEPREFIX="$WINEPREFIX" "$WINEBIN" wineboot -i
  echo "wineboot exit=$?"
  env -u DISPLAY WINEDEBUG=-all WINEPREFIX="$WINEPREFIX" "$WINEBIN" reg add \
    'HKCU\Software\Wine\Drivers' /v Graphics /t REG_SZ /d wayland /f || true
  capture notepadpp 120 env -u DISPLAY WINEDEBUG=-all WINEPREFIX="$WINEPREFIX" \
    "$WINEBIN" "$NPP_DIR/notepad++.exe" || echo "notepadpp soft-fail"
  [ -f "$PROOF/notepadpp.png" ] && png_ok "$PROOF/notepadpp.png" 4000 || true
  set -e
fi

echo "Proof artifacts:"; ls -la "$PROOF"/*.png
test -f "$PROOF/simple-shm.png"
test -f "$PROOF/mousepad.png"
test -f "$PROOF/galculator.png"
png_ok "$PROOF/mousepad.png" 6000
png_ok "$PROOF/galculator.png" 4000
echo "arm64 guest proof OK"
