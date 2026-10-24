#!/bin/sh
# Build Mesa's Turnip Vulkan driver with the KGSL kernel backend (Qualcomm Android /dev/kgsl-3d0), inside Debian 13.
# Output: $OUT/turnip.tar.xz containing libvulkan_freedreno.so (ICD json is written on device by hatch-gpu-check).
# Usage (in a Debian 13 rootfs/container, as root): sh build-turnip.sh [OUT_DIR]
set -eu
MESA=${MESA_VERSION:-25.3.6}
OUT=${1:-/out}; mkdir -p "$OUT"
apt-get update -qq
apt-get install -y -qq --no-install-recommends ca-certificates curl xz-utils build-essential meson ninja-build pkg-config \
  python3-mako python3-yaml python3-packaging bison flex glslang-tools libdrm-dev libexpat1-dev zlib1g-dev libzstd-dev \
  libwayland-dev wayland-protocols libwayland-bin libx11-dev libx11-xcb-dev libxcb1-dev libxcb-dri3-dev libxcb-present-dev \
  libxcb-randr0-dev libxcb-shm0-dev libxcb-sync-dev libxshmfence-dev libxrandr-dev libxext-dev libxfixes-dev libxxf86vm-dev \
  libelf-dev libudev-dev >/dev/null
cd /tmp && rm -rf "mesa-$MESA"
curl -fsSL "https://archive.mesa3d.org/mesa-$MESA.tar.xz" | tar -xJ
cd "mesa-$MESA"
meson setup build --buildtype=release -Dstrip=true -Dplatforms=wayland,x11 \
  -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl -Dgallium-drivers= \
  -Dopengl=false -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dgles1=disabled -Dgles2=disabled \
  -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dzstd=enabled -Dvideo-codecs= \
  -Dvulkan-layers= -Dtools= -Dbuild-tests=false >/tmp/meson.log 2>&1 || { tail -40 /tmp/meson.log; exit 1; }
ninja -C build src/freedreno/vulkan/libvulkan_freedreno.so
D=$(mktemp -d); cp build/src/freedreno/vulkan/libvulkan_freedreno.so "$D/"
echo "mesa-$MESA turnip kgsl $(uname -m) $(date -u +%F)" > "$D/VERSION"
tar -C "$D" -cJf "$OUT/turnip.tar.xz" .
ls -la "$OUT/turnip.tar.xz"; ldd "$D/libvulkan_freedreno.so" | awk '{print $1}' | tr '\n' ' '; echo
