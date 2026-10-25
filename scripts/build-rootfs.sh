#!/usr/bin/env bash
# Prebake Hatch's Debian 13 rootfs: debian:trixie + rootfs/base-packages.txt + the hatch user + XDG dirs,
# exported as one tar.zst the app stream-extracts (download → zstd → files, one pass).
# Usage: build-rootfs.sh <arm64|amd64> <outdir>     (run natively: arm64 on an arm64 runner)
set -euo pipefail
ARCH=$1; OUT=$2; ROOT="$(cd "$(dirname "$0")/.." && pwd)"; mkdir -p "$OUT"
PKGS=$(grep -v '^\s*#' "$ROOT/rootfs/base-packages.txt" | tr '\n' ' ' | tr -s ' ')
NAME="hatch-rootfs-debian13-$ARCH"; C="hatch-rootfs-$ARCH-$$"
t0=$(date +%s)
docker pull -q --platform "linux/$ARCH" debian:trixie
docker run --platform "linux/$ARCH" --name "$C" -e PKGS="$PKGS" debian:trixie bash -euo pipefail -c '
  export DEBIAN_FRONTEND=noninteractive
  # Same dpkg/apt policy the app writes (GuestConfig): no docs/man pages, no recommends.
  printf "path-exclude=/usr/share/doc/*\npath-exclude=/usr/share/man/*\npath-exclude=/usr/share/info/*\n" > /etc/dpkg/dpkg.cfg.d/01-hatch
  printf "APT::Install-Recommends \"false\";\nAcquire::Languages \"none\";\n" > /etc/apt/apt.conf.d/99hatch
  apt-get update
  apt-get install -y --no-install-recommends $PKGS
  update-ca-certificates
  getent passwd hatch >/dev/null || useradd -m -u 1000 -U -s /bin/bash -c Hatch hatch
  for d in Desktop Documents Downloads Music Pictures Videos .config .local/share .cache; do install -d -o hatch -g hatch /home/hatch/$d; done
  install -d -m 0700 -o hatch -g hatch /run/hatch
  install -d /opt/hatch /root
  apt-get clean
  rm -rf /var/lib/apt/lists/* /var/cache/apt/*.bin /var/log/* /tmp/* /var/tmp/* /usr/share/doc /usr/share/man /usr/share/info
  find /usr/share/locale -mindepth 1 -maxdepth 1 ! -name "en*" ! -name locale.alias -exec rm -rf {} +
  dpkg-query -W -f="\${Package} \${Version}\n" > /etc/hatch-rootfs-packages
'
INSTALLED=$(docker export "$C" | tar -t -v 2>/dev/null | awk '{s+=$3} END {print s}')
FILES=$(docker export "$C" | tar -t 2>/dev/null | wc -l)
# .dockerenv and the container's hostname/hosts/resolv.conf aren't part of the image (GuestConfig writes its own)
docker export "$C" | python3 "$ROOT/scripts/tar-filter.py" .dockerenv etc/hostname etc/hosts etc/resolv.conf \
  | zstd -19 -T0 -q -o "$OUT/$NAME.tar.zst"
docker cp "$C:/etc/hatch-rootfs-packages" "$OUT/$NAME.packages.txt"
docker rm -f "$C" >/dev/null
SHA=$(sha256sum "$OUT/$NAME.tar.zst" | cut -d' ' -f1); SIZE=$(stat -c%s "$OUT/$NAME.tar.zst")
python3 - "$OUT/$NAME.json" <<PY
import json,sys
json.dump({"arch":"$ARCH","file":"$NAME.tar.zst","sha256":"$SHA","size":$SIZE,"installedSize":$INSTALLED,"files":$FILES,
           "packages":$(wc -l < "$OUT/$NAME.packages.txt")}, open(sys.argv[1],"w"), indent=1)
PY
echo "built $NAME.tar.zst: $((SIZE / 1000000)) MB compressed, $((INSTALLED / 1000000)) MB installed, $FILES entries in $(( $(date +%s) - t0 ))s"
