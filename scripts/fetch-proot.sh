#!/usr/bin/env bash
# Fetch arm64 proot + loader into app/src/main/jniLibs/arm64-v8a as libproot.so / libprootloader.so.
# Source: termux-packages proot build (same strategy Kern uses). Re-run when bumping.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
echo "TODO: wire Termux proot .deb / GitHub release download into $OUT"
echo "Expected outputs: libproot.so libprootloader.so (and any libc needed beside them)"
echo "Also set packaging.jniLibs.useLegacyPackaging=true (already in app/build.gradle.kts)."
