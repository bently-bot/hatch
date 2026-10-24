#!/bin/sh
# Build the guest-side helper libraries (glibc, for the Debian rootfs) into <out>/<android-abi>/.
set -eu
OUT=${1:?out dir}; SRC=$(dirname "$0")/../guest-src
build() { # abi cc
  command -v "$2" >/dev/null 2>&1 || { echo "skip $1 (no $2)"; return 0; }
  mkdir -p "$OUT/$1"
  "$2" -shared -fPIC -O2 -Wall -o "$OUT/$1/libhatch-execmod.so" "$SRC/hatch-execmod.c" -ldl
  echo "built $1/libhatch-execmod.so"
}
case "$(uname -m)" in
  x86_64) build x86_64 gcc; build arm64-v8a aarch64-linux-gnu-gcc ;;
  aarch64) build arm64-v8a gcc; build x86_64 x86_64-linux-gnu-gcc ;;
esac
