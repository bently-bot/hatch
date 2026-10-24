#!/usr/bin/env bash
# Fetch Termux-built proot (+loader, libtalloc, libandroid-shmem) for an ABI into app/src/main/jniLibs/<abi>/.
# usage: scripts/fetch-proot.sh [aarch64|x86_64]   (x86_64 is for KVM emulator E2E tests)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARCH="${1:-aarch64}"
case "$ARCH" in aarch64) ABI=arm64-v8a ;; x86_64) ABI=x86_64 ;; *) echo "bad arch"; exit 2 ;; esac
PROOT_VER="${PROOT_VER:-5.1.107.101}"
B=https://packages.termux.dev/apt/termux-main/pool/main
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
for u in "p/proot/proot_${PROOT_VER}_${ARCH}.deb" "libt/libtalloc/libtalloc_2.5.0_${ARCH}.deb" "liba/libandroid-shmem/libandroid-shmem_0.7_${ARCH}.deb"; do
  curl -fsSL -o "$T/$(basename "$u")" "$B/$u"
done
for d in "$T"/*.deb; do dpkg-deb -x "$d" "$T/x"; done
P="$T/x/data/data/com.termux/files/usr"
OUT="$ROOT/app/src/main/jniLibs/$ABI"; mkdir -p "$OUT"
cp "$P/bin/proot" "$OUT/libproot.so"
cp "$P/libexec/proot/loader" "$OUT/libproot_loader.so"
[ -f "$P/libexec/proot/loader32" ] && cp "$P/libexec/proot/loader32" "$OUT/libproot_loader32.so"
cp -L "$P/lib/libtalloc.so.2" "$OUT/libtalloc.so"
cp "$P/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"
# Android only loads lib*.so from nativeLibraryDir: rename DT_NEEDED libtalloc.so.2 -> libtalloc.so (same-length patch)
python3 - "$OUT/libproot.so" <<'PY'
import sys
p = sys.argv[1]; b = open(p, "rb").read()
old = b"libtalloc.so.2\0"; new = b"libtalloc.so\0\0\0"
assert b.count(old) >= 1, "NEEDED not found"
open(p, "wb").write(b.replace(old, new))
PY
ls -la "$OUT"
