#!/usr/bin/env bash
# Before/after Debian setup timings on the arm64 runner, with the app's own code paths:
#   BEFORE  linuxcontainers rootfs.tar.xz → tukaani xz → tar (proot -0 -l, like the app) → apt-get update + base install
#   AFTER   prebuilt hatch-rootfs tar.zst streamed: download → sha256 → zstd → TarExtractor (one pass)
# Then sanity-checks the new rootfs under termux proot (link2symlink hard links, apt, the hatch user).
# Env: ZST (file or URL), ZST_SHA, PROOF_DIR. Needs: kotlinc, java, PROOT (built by ci-guest-proof's recipe).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
WORK="${RUNNER_TEMP:-/tmp/hatch-bench}"; OUT="${PROOF_DIR:-$ROOT/harness-out}"; mkdir -p "$WORK" "$OUT" build/bench
R="$OUT/rootfs-bench.txt"; : > "$R"
log() { echo "$*" | tee -a "$R"; }
now() { date +%s.%N; }; el() { python3 -c "print(f'{$(now)-$1:.1f}')"; }
MVN=https://repo1.maven.org/maven2
curl -fsSL -o "$WORK/zstd-jni.jar" $MVN/com/github/luben/zstd-jni/1.5.7-24/zstd-jni-1.5.7-24.jar
curl -fsSL -o "$WORK/xz.jar" $MVN/org/tukaani/xz/1.10/xz-1.10.jar
KLIB="$(dirname "$(dirname "$(readlink -f "$(command -v kotlinc)")")")/lib"
CP="$WORK/zstd-jni.jar:$WORK/xz.jar"
kotlinc -d build/bench -cp "$CP" -jvm-target 17 app/src/main/kotlin/dev/hatch/rootfs/TarExtractor.kt harness/src/dev/hatch/harness/RootfsBench.kt 2>&1 | grep -v '^warning' || true
RUN="java -cp build/bench:$CP:$KLIB/kotlin-stdlib.jar dev.hatch.harness.RootfsBenchKt"
pg() { # proot like the app (fake root, link2symlink dir), command in the guest
  local root=$1; shift
  PROOT_L2S_DIR="$root/.l2s" PROOT_NO_SECCOMP=1 "$PROOT" -0 -l -r "$root" -b /dev -b /proc -b /sys -b "$root/.l2s" -w /root \
    /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME=/root DEBIAN_FRONTEND=noninteractive "$@"
}
log "runner: $(uname -m), $(nproc) cpus, $(free -g | awk '/Mem/{print $2}') GB RAM"

# ---------- AFTER ----------
NEW="$WORK/new-root"; rm -rf "$NEW"
s=$(now); $RUN new "$ZST" "$NEW" "${ZST_SHA:-}" | tee -a "$R"; T_NEW=$(el $s)
log "AFTER total (download+verify+decompress+extract, one pass): ${T_NEW}s  [source: $ZST]"
s=$(now)
pg "$NEW" /bin/sh -c '
  set -e
  id hatch; test -d /home/hatch/Downloads
  ls -l /usr/bin/perl* 2>/dev/null | head -3 || true
  perl -e "print qq(perl ok\n)" 2>/dev/null || echo "(no perl)"
  echo l2s-src > /tmp/a; ln /tmp/a /tmp/b; ln /tmp/b /tmp/c; rm /tmp/a; cat /tmp/c; stat -c "links=%h" /tmp/c
  for p in xwayland libwayland-cursor0 lsof fonts-dejavu-core mesa-libgallium libgl1-mesa-dri pulseaudio; do dpkg -s $p >/dev/null 2>&1 && echo "pkg $p ok" || echo "pkg $p MISSING"; done
  dpkg --audit && echo "dpkg audit clean"
' 2>&1 | tee -a "$R"
grep -q 'MISSING' "$R" && { log "FAIL: base package missing in prebuilt rootfs"; exit 1; }
HL=$(find "$NEW/.l2s" -name '.l2s.*' 2>/dev/null | wc -l); log "l2s entries from extraction: $HL"
log "AFTER guest sanity ${s:+$(el $s)s}"
pg "$NEW" /bin/sh -c 'apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq --no-install-recommends galculator >/dev/null 2>&1 && echo "apt install in prebuilt rootfs OK"' 2>&1 | tee -a "$R"
rm -rf "$NEW"

# ---------- BEFORE ----------
RF=app/src/main/kotlin/dev/hatch/guest/RootfsCatalog.kt
URL=$(grep -A1 'ROOTFS_URL' "$RF" | grep -o 'https://[^"]*' | head -1); OLD="$WORK/old-root"; rm -rf "$OLD"; mkdir -p "$OLD/.l2s"
s=$(now); curl -fsSL -o "$WORK/old.tar.xz" "$URL" || { base=${URL%/*/rootfs.tar.xz}; latest=$(curl -fsSL "$base/" | grep -o '[0-9]\{8\}_[0-9][0-9]:[0-9][0-9]' | sort | tail -1); curl -fsSL -o "$WORK/old.tar.xz" "$base/$latest/rootfs.tar.xz"; }
T_DL=$(el $s); log "BEFORE download $(( $(stat -c%s "$WORK/old.tar.xz") / 1000000 ))MB: ${T_DL}s"
s=$(now); $RUN xz "$WORK/old.tar.xz" "$WORK/old.tar" | tee -a "$R"; T_XZ=$(el $s)
s=$(now); PROOT_L2S_DIR="$OLD/.l2s" "$PROOT" -0 -l -r / tar -xf "$WORK/old.tar" -C "$OLD" --exclude='dev/*' 2>/dev/null || true; T_TAR=$(el $s)
log "BEFORE proot tar: ${T_TAR}s"
rm -f "$WORK/old.tar" "$WORK/old.tar.xz"
printf 'nameserver 1.1.1.1\n' > "$OLD/etc/resolv.conf" 2>/dev/null || { rm -f "$OLD/etc/resolv.conf"; printf 'nameserver 1.1.1.1\n' > "$OLD/etc/resolv.conf"; }
OLDBASE="ca-certificates curl xz-utils libwayland-client0 libwayland-cursor0 libxkbcommon0 fontconfig fonts-dejavu-core"
s=$(now); pg "$OLD" /bin/sh -c "apt-get update -qq && apt-get install -y -qq --no-install-recommends $OLDBASE" >/dev/null 2>&1 || log "WARN: old base install failed"; T_APT=$(el $s)
log "BEFORE apt update + v0.1 base: ${T_APT}s"
FULL=$(grep -v '^\s*#' rootfs/base-packages.txt | tr '\n' ' ')
s=$(now); pg "$OLD" /bin/sh -c "apt-get install -y -qq --no-install-recommends $FULL" >/dev/null 2>&1 || log "WARN: full base install failed"; T_FULL=$(el $s)
log "BEFORE + rest of the base (Xwayland, Mesa, audio — paid later on first app install in v0.1): ${T_FULL}s"
rm -rf "$OLD"
python3 - "$R" "$T_NEW" "$T_DL" "$T_XZ" "$T_TAR" "$T_APT" "$T_FULL" <<'PY'
import sys
r,new,dl,xz,tar,apt,full=sys.argv[1],*map(float,sys.argv[2:])
before=dl+xz+tar+apt
open(r,"a").write(f"\nSUMMARY arm64 runner: BEFORE {before:.0f}s (download {dl:.0f} + xz {xz:.0f} + proot tar {tar:.0f} + apt {apt:.0f}); "
                  f"+{full:.0f}s for Xwayland/Mesa/audio later  |  AFTER {new:.0f}s with all of it baked in\n")
print(open(r).read().splitlines()[-1])
PY
