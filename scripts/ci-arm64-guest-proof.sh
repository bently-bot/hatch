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
  mousepad libgtk-3-0 \
  wine64 wine libwine fonts-dejavu-core || \
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
  gcc make weston curl ca-certificates mousepad wine64

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
  rm -f "$PROOF/$name.png" "$PROOF/${name}-client.log" "$PROOF/${name}-host.log"
  set +e
  # Clear DISPLAY for the whole JVM child tree
  env -u DISPLAY java -Dhatch.native="$NAT" -cp "$CP" \
    dev.hatch.harness.MainKt \
    "$RUNNER_TEMP/wayland-$name" \
    "$PROOF/$name.png" \
    "$seconds" -- "$@" >"$PROOF/${name}-host.log" 2>&1
  local rc=$?
  set -e
  echo "==== $name host (tail) ===="; tail -30 "$PROOF/${name}-host.log" || true
  [ -f "$PROOF/client.log" ] && mv "$PROOF/client.log" "$PROOF/${name}-client.log" || true
  echo "==== $name client (tail) ===="; tail -40 "$PROOF/${name}-client.log" 2>/dev/null || true
  [ -f "$PROOF/$name.png" ] && ls -la "$PROOF/$name.png" && return 0
  return "$rc"
}

capture simple-shm 15 weston-simple-shm

# mousepad — REQUIRED for green arm64 proof of a real GTK app
capture mousepad 30 mousepad

# Wine/Notepad++ — best-effort (wineboot can be slow even on arm64)
if command -v wine64 >/dev/null || command -v wine >/dev/null; then
  NPP_DIR="$RUNNER_TEMP/npp"
  mkdir -p "$NPP_DIR"
  curl -fsSL -o /tmp/npp.zip \
    https://github.com/notepad-plus-plus/notepad-plus-plus/releases/download/v8.9.8.1/npp.8.9.8.1.portable.arm64.zip
  unzip -qo /tmp/npp.zip -d "$NPP_DIR"
  export WINEPREFIX="$RUNNER_TEMP/wineprefix"
  WINEBIN=$(command -v wine64 || command -v wine)
  echo "wineboot -i (max 4 min)…"
  set +e
  timeout 240 env -u DISPLAY WINEDEBUG=-all WINEPREFIX="$WINEPREFIX" "$WINEBIN" wineboot -i
  echo "wineboot exit=$?"
  env -u DISPLAY WINEDEBUG=-all WINEPREFIX="$WINEPREFIX" "$WINEBIN" reg add \
    'HKCU\Software\Wine\Drivers' /v Graphics /t REG_SZ /d wayland /f || true
  capture notepadpp 90 env -u DISPLAY WINEDEBUG=-all WINEPREFIX="$WINEPREFIX" \
    "$WINEBIN" "$NPP_DIR/notepad++.exe" || echo "notepadpp soft-fail"
  set -e
fi

echo "Proof artifacts:"; ls -la "$PROOF"/*.png
test -f "$PROOF/simple-shm.png"
test -f "$PROOF/mousepad.png"
