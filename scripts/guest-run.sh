#!/usr/bin/env bash
# Run a command inside the CI/test Debian rootfs under proot, mirroring LinuxRuntime.prootArgv/prootEnv.
# Env: HATCH_ROOTFS (required), PROOT (default: proot), XDG_RUNTIME_DIR (host Wayland socket dir → /run/hatch)
set -euo pipefail
R="${HATCH_ROOTFS:?HATCH_ROOTFS not set}"
PROOT="${PROOT:-proot}"
L2S="$R/.l2s"; mkdir -p "$L2S" "${HATCH_PTMP:=/tmp/hatch-ptmp}"
binds=(-b /dev -b /proc -b /sys -b "$L2S:$L2S")
[ -d /dev/pts ] && binds+=(-b /dev/pts)
if [ -n "${XDG_RUNTIME_DIR:-}" ] && [ -d "$XDG_RUNTIME_DIR" ]; then binds+=(-b "$XDG_RUNTIME_DIR:/run/hatch"); fi
export PROOT_TMP_DIR="$HATCH_PTMP" PROOT_L2S_DIR="$L2S"
# The app's bundled (Termux) proot needs -l (+PROOT_L2S_DIR) because Android forbids hard links in app data.
# Stock distro proot's link2symlink ignores PROOT_L2S_DIR and corrupts dpkg's status on re-link, and
# hard links work on CI hosts anyway, so -l is opt-in here (HATCH_L2S=1).
l2s=(); [ "${HATCH_L2S:-0}" = 1 ] && l2s=(-l)
exec "$PROOT" -0 "${l2s[@]}" -r "$R" "${binds[@]}" -w /root \
  /usr/bin/env -i \
  HOME=/root USER=root LOGNAME=root LANG=C.UTF-8 TERM=xterm-256color TMPDIR=/tmp \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  DEBIAN_FRONTEND=noninteractive \
  XDG_RUNTIME_DIR=/run/hatch WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}" \
  GDK_BACKEND=wayland QT_QPA_PLATFORM=wayland SDL_VIDEODRIVER=wayland \
  WINEPREFIX=/root/.wine LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  "$@"
