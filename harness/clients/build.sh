#!/usr/bin/env bash
# Builds harness/clients/dmabuf-test (needs libwayland-dev, wayland-protocols, wayland-scanner).
set -euo pipefail
cd "$(dirname "$0")"; OUT="${1:-../../build/host}"; mkdir -p "$OUT/gen"
P=/usr/share/wayland-protocols
for x in "$P/unstable/linux-dmabuf/linux-dmabuf-unstable-v1.xml" "$P/stable/xdg-shell/xdg-shell.xml"; do
  n=$(basename "$x" .xml)
  wayland-scanner client-header "$x" "$OUT/gen/$n-client-protocol.h"
  wayland-scanner private-code "$x" "$OUT/gen/$n-protocol.c"
done
gcc -O2 -Wall -I"$OUT/gen" -o "$OUT/dmabuf-test" dmabuf-test.c "$OUT"/gen/*-protocol.c -lwayland-client
echo "built $OUT/dmabuf-test"
