#!/usr/bin/env bash
# Native arm64 proof: host Wayland compositor + Debian arm64 guest (proot) → mousepad PNG;
# Wine arm64 Notepad++ PNG if wine installs in time.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PROOF="${PROOF_DIR:-$ROOT/harness-out}"
mkdir -p "$PROOF" build/host

sudo apt-get update -qq
sudo apt-get install -y -qq gcc make weston proot curl xz-utils unzip \
  wine64 wine libwine ca-certificates fonts-dejavu-core mousepad 2>/dev/null \
  || sudo apt-get install -y -qq gcc make weston proot curl xz-utils unzip ca-certificates

# Build host compositor
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
  rm -f "$PROOF/$name.png"
  java -Dhatch.native="$NAT" -cp "$CP" \
    dev.hatch.harness.MainKt \
    "$RUNNER_TEMP/wayland-$name" \
    "$PROOF/$name.png" \
    "$seconds" -- "$@" || return 1
  ls -la "$PROOF/$name.png"
}

# 1) weston-simple-shm (system)
if command -v weston-simple-shm >/dev/null; then
  capture simple-shm 15 weston-simple-shm
fi

# 2) mousepad if installed
if command -v mousepad >/dev/null; then
  capture mousepad 20 env GDK_BACKEND=wayland mousepad || true
fi

# 3) Wine + Notepad++ arm64 portable
if command -v wine64 >/dev/null || command -v wine >/dev/null; then
  NPP_DIR="$RUNNER_TEMP/npp"
  mkdir -p "$NPP_DIR"
  curl -fsSL -o /tmp/npp.zip \
    https://github.com/notepad-plus-plus/notepad-plus-plus/releases/download/v8.9.8.1/npp.8.9.8.1.portable.arm64.zip
  unzip -qo /tmp/npp.zip -d "$NPP_DIR"
  export WINEPREFIX="$RUNNER_TEMP/wineprefix"
  # Prefer wayland driver
  WINEDEBUG=-all wine64 reg add 'HKCU\Software\Wine\Drivers' /v Graphics /t REG_SZ /d wayland /f 2>/dev/null || true
  WINEBIN=$(command -v wine64 || command -v wine)
  capture notepadpp 90 env WINEDEBUG=-all WINEPREFIX="$WINEPREFIX" \
    GDK_BACKEND=wayland "$WINEBIN" "$NPP_DIR/notepad++.exe" || true
fi

echo "Proof artifacts:"; ls -la "$PROOF"/*.png 2>/dev/null || echo "(none)"
# Require at least simple-shm
test -f "$PROOF/simple-shm.png"
