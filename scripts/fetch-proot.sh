#!/usr/bin/env bash
# Hatch ships Termux-built proot binaries under app/src/main/jniLibs/arm64-v8a/.
# Re-run this if you need to refresh from Termux packages.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
echo "Current jniLibs:"
ls -la "$OUT"
echo "To refresh: download Termux aarch64 proot + libtalloc + libandroid-shmem .debs,"
echo "extract .so files, patchelf DT_NEEDED libtalloc.so.2 -> libtalloc.so, place in $OUT"
