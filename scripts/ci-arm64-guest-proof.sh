#!/usr/bin/env bash
# Native arm64 proof: host Wayland compositor + real clients → PNGs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PROOF="${PROOF_DIR:-$ROOT/harness-out}"
mkdir -p "$PROOF" build/host
export RUNNER_TEMP="${RUNNER_TEMP:-/tmp/hatch-ci}"
mkdir -p "$RUNNER_TEMP"

sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  gcc make weston proot curl xz-utils unzip ca-certificates \
  mousepad libgtk-3-0 libwayland-client0 \
  wine64 wine libwine fonts-dejavu-core || \
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  gcc make weston curl xz-utils unzip ca-certificates mousepad wine64

curl -fsSL -o /tmp/kotlin.zip https://github.com/JetBrains/kotlin/releases/download/v2.1.20/kotlin-compiler-2.1.20.zip
sudo unzip -qo /tmp/kotlin.zip -d /opt
export PATH="/opt/kotlinc/bin:$PATH"
gcc -shared -fPIC -O2 -DHATCH_HOST -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -o build/host/libhatchnative.so app/src/main/cpp/hatchnative.c -lpthread
kotlinc -d build/host/classes -jvm-target 17 \
  app/src/main/kotlin/dev/hatch/wayland/*.kt harness/src/dev/hatch/harness/Main.kt
CP="build/host/classes:/opt/kotlinc/lib/kotlin-stdlib.jar"
NAT="$ROOT/build/host/libhatchnative.so"

capture() {
  local name="$1"; shift
  local seconds="$1"; shift
  rm -f "$PROOF/$name.png" "$PROOF/${name}-client.log"
  set +e
  java -Dhatch.native="$NAT" -cp "$CP" \
    dev.hatch.harness.MainKt \
    "$RUNNER_TEMP/wayland-$name" \
    "$PROOF/$name.png" \
    "$seconds" -- "$@" >"$PROOF/${name}-host.log" 2>&1
  local rc=$?
  set -e
  echo "---- $name host log (tail) ----"
  tail -40 "$PROOF/${name}-host.log" || true
  if [ -f "$PROOF/client.log" ]; then mv "$PROOF/client.log" "$PROOF/${name}-client.log"; fi
  # harness writes client.log next to png
  if [ -f "$(dirname "$PROOF/$name.png")/client.log" ]; then
    mv "$(dirname "$PROOF/$name.png")/client.log" "$PROOF/${name}-client.log" || true
  fi
  echo "---- $name client log (tail) ----"
  tail -80 "$PROOF/${name}-client.log" 2>/dev/null || true
  if [ -f "$PROOF/$name.png" ]; then
    ls -la "$PROOF/$name.png"
    return 0
  fi
  return "$rc"
}

# 1) weston-simple-shm
capture simple-shm 15 weston-simple-shm

# 2) mousepad — disable accessibility bridge; force wayland
if command -v mousepad >/dev/null; then
  capture mousepad 25 env NO_AT_BRIDGE=1 GTK_A11Y=none GDK_BACKEND=wayland mousepad \
    || echo "mousepad capture failed (see logs)"
fi

# 3) Wine + Notepad++ — wineboot FIRST (can take minutes), then launch
if command -v wine64 >/dev/null || command -v wine >/dev/null; then
  NPP_DIR="$RUNNER_TEMP/npp"
  mkdir -p "$NPP_DIR"
  curl -fsSL -o /tmp/npp.zip \
    https://github.com/notepad-plus-plus/notepad-plus-plus/releases/download/v8.9.8.1/npp.8.9.8.1.portable.arm64.zip
  unzip -qo /tmp/npp.zip -d "$NPP_DIR"
  export WINEPREFIX="$RUNNER_TEMP/wineprefix"
  WINEBIN=$(command -v wine64 || command -v wine)
  echo "Running wineboot -i (up to 8 min)…"
  set +e
  timeout 480 env WINEDEBUG=-all WINEPREFIX="$WINEPREFIX" "$WINEBIN" wineboot -i
  echo "wineboot exit=$?"
  # Set wayland graphics driver
  env WINEDEBUG=-all WINEPREFIX="$WINEPREFIX" "$WINEBIN" reg add \
    'HKCU\Software\Wine\Drivers' /v Graphics /t REG_SZ /d wayland /f
  set -e
  capture notepadpp 120 env WINEDEBUG=-all WINEPREFIX="$WINEPREFIX" \
    "$WINEBIN" "$NPP_DIR/notepad++.exe" \
    || echo "notepadpp capture failed (see logs)"
fi

echo "Proof artifacts:"
ls -la "$PROOF"/*.png 2>/dev/null || echo "(none)"
test -f "$PROOF/simple-shm.png"
# Soft-require mousepad/npp but don't fail the job if wineboot too slow
