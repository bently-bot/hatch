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
for b in ${HATCH_EXTRA_BINDS:-}; do binds+=(-b "$b"); done
export PROOT_TMP_DIR="$HATCH_PTMP" PROOT_L2S_DIR="$L2S"
# The app's bundled (Termux) proot needs -l (+PROOT_L2S_DIR) because Android forbids hard links in app data.
# Stock distro proot's link2symlink ignores PROOT_L2S_DIR and corrupts dpkg's status on re-link, and
# hard links work on CI hosts anyway, so -l is opt-in here (HATCH_L2S=1).
l2s=(); [ "${HATCH_L2S:-0}" = 1 ] && l2s=(-l)
# HATCH_AS_USER=1 mirrors app launches (proot -i 1000:1000, user "hatch"); default is fake root like installs.
if [ "${HATCH_AS_USER:-0}" = 1 ]; then id=(-i 1000:1000); H=/home/hatch; U=hatch; else id=(-0); H=/root; U=root; fi
# Flathub apps: "hatch-flatpak-run <app-id> [args]" runs from the spec hatch-flatpak wrote (same as FlatpakSpec.kt).
if [ "${1:-}" = hatch-flatpak-run ]; then
  SPEC="$R/var/lib/hatch/flatpak/$2.spec"; shift 2
  [ -f "$SPEC" ] || { echo "no flatpak spec $SPEC" >&2; exit 4; }
  froot=""; fb=(-b /dev -b /proc -b /sys -b "$L2S:$L2S"); fenv=(); fcmd=()
  [ -n "${XDG_RUNTIME_DIR:-}" ] && [ -d "$XDG_RUNTIME_DIR" ] && fb+=(-b "$XDG_RUNTIME_DIR:/run/hatch")
  while IFS= read -r line; do
    case "$line" in
      "root "*) froot="$R${line#root }" ;;
      "bind "*) b=${line#bind }; fb+=(-b "$R${b%%:*}:${b#*:}") ;;
      "env "*) fenv+=("${line#env }") ;;
      "cmd "*) read -r -a fcmd <<< "${line#cmd }" ;;
    esac
  done < "$SPEC"
  exec "$PROOT" -i 1000:1000 "${l2s[@]}" -r "$froot" "${fb[@]}" -w /home/hatch /usr/bin/env -i \
    HOME=/home/hatch USER=hatch LOGNAME=hatch LANG=C.UTF-8 TERM=xterm-256color \
    XDG_RUNTIME_DIR=/run/hatch WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}" GDK_BACKEND=wayland QT_QPA_PLATFORM=wayland \
    SDL_VIDEODRIVER=wayland LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
    "${fenv[@]}" "${fcmd[@]}" "$@"
fi
exec "$PROOT" "${id[@]}" "${l2s[@]}" -r "$R" "${binds[@]}" -w "$H" \
  /usr/bin/env -i \
  HOME=$H USER=$U LOGNAME=$U LANG=C.UTF-8 TERM=xterm-256color \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games \
  DEBIAN_FRONTEND=noninteractive \
  XDG_RUNTIME_DIR=/run/hatch WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}" \
  GDK_BACKEND=wayland QT_QPA_PLATFORM=wayland SDL_VIDEODRIVER=wayland \
  WINEPREFIX=$H/.wine LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  "$@"
