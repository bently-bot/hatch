# Sourced by ci-guest-proof.sh when SWEEP=1 (uses its rootfs, harness, G/GU, capture, png_ok, title_for, stamp).
# Every app visible in the store's rows (Featured, Windows apps, Popular on Flathub, Classics, plus experimental
# Steam) is installed with the real hatch-install-app, then launched with the real hatch-launch argv on each rung of
# its renderer ladder (scripts/renderer-ladder.py ≙ gpu/Renderers.kt) until one draws, like the app's auto-fallback.
# A rung passes when Hatch's compositor reports real content for the window (the same black-frame check as on the
# phone: WaylandServer "content '…'") and the screenshot isn't empty. GPU rungs use the Adreno stand-in: Zink over
# lavapipe with the software WSI (exactly Gpu.env(true) minus the Turnip ICD).
# Env: SHARD/NSHARDS (split the list), SWEEP_APPS (override), SWEEP_GPU=1 (default)
CAT=catalogue/catalogue.json
SWEEP_APPS="${SWEEP_APPS:-$(python3 - "$CAT" <<'PY'
import json, sys
apps = json.load(open(sys.argv[1]))["apps"]
arm = [a for a in apps if "arm64" in (a.get("arches") or ["arm64"])]
featured = [a["id"] for a in arm if a.get("featured")]
wine = [a["id"] for a in arm if a.get("wine")]
flathub = [a["id"] for a in arm if a.get("source") == "flathub"][:16]
CLASSICS = ["emacs", "geany", "dosbox", "scummvm", "kate", "qtcreator", "thonny", "bluefish", "xterm"]   # HatchUi.kt
ids = {a["id"] for a in arm}
print(" ".join(dict.fromkeys(featured + ["hdrview", "steam"] + wine + flathub + [c for c in CLASSICS if c in ids])))
PY
)}"
NSHARDS=${NSHARDS:-1}; SHARD=${SHARD:-0}
LIST=(); i=0; for a in $SWEEP_APPS; do [ $((i % NSHARDS)) -eq "$SHARD" ] && LIST+=("$a"); i=$((i + 1)); done
stamp "sweep shard $SHARD/$NSHARDS: ${LIST[*]}"
TSV="$PROOF/sweep-$SHARD.tsv"; : > "$TSV"
mkdir -p "$PROOF/sweep"
BASEPKGS=" $(grep -v '^\s*#' rootfs/base-packages.txt | tr '\n' ' ') "

if [ "${SWEEP_GPU:-1}" = 1 ]; then
  "${G[@]}" bash -c 'DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends mesa-vulkan-drivers libvulkan1 >/tmp/gpu-apt.log 2>&1; tail -1 /tmp/gpu-apt.log'
  LVP=$("${G[@]}" bash -c 'ls /usr/share/vulkan/icd.d/lvp_icd*.json | head -1' | tr -d '\r')
fi
# Gpu.env(true) with lavapipe standing in for Turnip; Gpu.env(false)
ZENV="LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=zink MESA_LOADER_DRIVER_OVERRIDE=zink VK_ICD_FILENAMES=${LVP:-} VK_DRIVER_FILES=${LVP:-} TU_DEBUG=noconform MESA_VK_WSI_DEBUG=sw LIBGL_KOPPER_DRI2=1 HATCH_GPU=1 D3D_ALWAYS_SOFTWARE=1"
SENV="LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe MESA_LOADER_DRIVER_OVERRIDE=llvmpipe LP_NUM_THREADS=$(nproc)"

err_line() { [ -f "$1" ] || return 0
  grep -aiE 'error|fatal|failed|cannot|abort|segmentation|not found|denied|unable' "$1" | grep -avE 'Gtk-WARNING|dbus|org\.freedesktop|libEGL warning: DRI3|MESA-LOADER|xkbcomp|Gdk-Message' | tail -1 | tr '\t' ' ' | cut -c1-170; }
classify() { # name minu → "OK" or a reason
  local n=$1 h="$PROOF/$1-host.log" c="$PROOF/$1-client.log"
  if grep -q "content '" "$h" && [ -f "$PROOF/$n.png" ] && png_ok "$PROOF/$n.png" "$2" >/dev/null 2>&1; then
    if grep -q 'client exited' "$h"; then echo "drew, then exited: $(err_line "$c")"; else echo OK; fi; return; fi
  if ! grep -q 'window created' "$h"; then
    if grep -q 'client exited' "$h"; then echo "exited before a window ($(grep -o 'code=[-0-9]*' "$h" | tail -1)): $(err_line "$c")"
    else echo "no window: $(err_line "$c")"; fi; return; fi
  if ! grep -q "content '" "$h"; then echo "black window ($(grep -o 'windows: .*' "$h" | tail -1 | cut -c1-80)): $(err_line "$c")"; return; fi
  echo "drew, but the screenshot looks empty"
}
cleanup_app() { # keep the runner's disk small: purge what the app pulled in (never the base set)
  local pk; pk=$(python3 -c "import json,sys; a=next(x for x in json.load(open('$CAT'))['apps'] if x['id']=='$1'); print(' '.join(a.get('packages',[])))")
  local purge=(); for p in $pk; do [[ "$BASEPKGS" == *" $p "* ]] || purge+=("$p"); done
  [ ${#purge[@]} -eq 0 ] || "${G[@]}" bash -c "apt-get purge -y -q --autoremove ${purge[*]} >/dev/null 2>&1; apt-get clean; true" || true
  "${G[@]}" bash -c 'rm -rf /home/hatch/.local/share/Steam /home/hatch/.cache/* /tmp/hatch-dl-* /var/cache/apt/archives/partial/* 2>/dev/null; true' || true
}

for app in "${LIST[@]}"; do
  name=$(python3 -c "import json; print(next(x for x in json.load(open('$CAT'))['apps'] if x['id']=='$app')['name'])")
  stamp "== sweep: $app ($name) =="
  ti=$(date +%s)
  if ! "${G[@]}" bash -c "$(python3 scripts/catalogue-args.py install "$app" "$CAT") > /tmp/hatch-install-$app.log 2>&1; rc=\$?; [ \$rc = 0 ] || grep -E '^(E:|dpkg: )|rror|hatch:' /tmp/hatch-install-$app.log | tail -3; exit \$rc" > "$PROOF/sweep/$app-install.txt" 2>&1; then
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$app" "$name" "-" "-" FAIL "install failed: $(tail -1 "$PROOF/sweep/$app-install.txt" | tr '\t' ' ' | cut -c1-170)" >> "$TSV"
    stamp "$app: install FAILED"; cleanup_app "$app"; continue
  fi
  INST=$(( $(date +%s) - ti ))
  LAUNCH=$(python3 scripts/catalogue-args.py launch "$app" "$CAT")
  final=FAIL; tried=(); reason=""; kind=""
  while IFS='|' read -r rid rgpu rx11 rargs renv rkind; do
    kind=$rkind
    E=$SENV; [ "$rgpu" = 1 ] && [ "${SWEEP_GPU:-1}" = 1 ] && E="$ZENV"
    case "$LAUNCH" in
      hatch-flatpak-run*) RUNARGS=(${LAUNCH}) ;;
      *)
        L="$LAUNCH"
        # x11 override for this rung (Chromium on the X11 GPU rung / back to Wayland)
        if [ "$rx11" = 1 ] && [[ "$L" != *" --x11 "* ]]; then L="${L/hatch-launch /hatch-launch --x11 }"; fi
        if [ "$rx11" = 0 ]; then L="${L/ --x11 / }"; fi
        dbg=""; [ "$kind" = chromium ] && dbg="--enable-logging=stderr --v=1"
        RUNARGS=(bash -c "env $E $renv $L $rargs $dbg") ;;
    esac
    n="sweep-$app-$rid"
    secs=120; settle=4000; minu=12
    case "$kind" in chromium) secs=200; settle=25000 ;; steam) secs=900; settle=120000 ;; esac
    [[ "$LAUNCH" == *--x11* || "$rx11" = 1 ]] && settle=20000
    [ "$app" = notepadpp ] && secs=420; [ "$app" = blender ] && { secs=300; settle=60000; }
    case "$app" in org.darktable.*|kdenlive|krita|libreoffice-*) secs=300; settle=30000 ;; esac
    [ "$app" = xterm ] && minu=2
    T=$(title_for "$app"); topt=(); [ -n "$T" ] && topt=(--title="$T")
    tl=$(date +%s)
    capture "$n" "$secs" "${topt[@]}" --settle=$settle -- "${GU[@]}" "${RUNARGS[@]}" >/dev/null 2>&1 || true
    r=$(classify "$n" "$minu"); dt=$(( $(date +%s) - tl ))
    tried+=("$rid:$([ "$r" = OK ] && echo ok || echo fail)")
    stamp "$app [$rid] ${dt}s → $r"
    # Keep artifacts small: GPU-related lines from Chromium's verbose log, a thumbnail, the host log
    if [ "$kind" = chromium ] || [ "$kind" = steam ]; then
      grep -aiE 'gpu_init|gpu process|GpuProcess|egl|angle|vulkan|glx|dmabuf|linux_dmabuf|zwp_|viz|compositing|SharedImage|ozone|gl_surface|GLImplementation|swiftshader|software' \
        "$PROOF/$n-client.log" 2>/dev/null | grep -av 'VERBOSE2' | tail -60 > "$PROOF/sweep/$n-gpu.txt" || true
    fi
    tail -c 6000 "$PROOF/$n-client.log" > "$PROOF/sweep/$n-client-tail.txt" 2>/dev/null || true
    # The tail is often only the aftermath of the rung's kill (proot gone → every syscall ENOSYS): keep the start and
    # the distinct errors too.
    head -c 5000 "$PROOF/$n-client.log" > "$PROOF/sweep/$n-client-head.txt" 2>/dev/null || true
    grep -aE 'ERROR|FATAL|rror:|cannot|failed' "$PROOF/$n-client.log" 2>/dev/null | grep -avE 'zygote_linux|Function not implemented' \
      | sed -E 's/^\[[0-9:./]+:[A-Z]+:/[/' | awk '!seen[$0]++' | head -60 > "$PROOF/sweep/$n-errors.txt" || true
    cp "$PROOF/$n-host.log" "$PROOF/sweep/$n-host.txt" 2>/dev/null || true
    [ -f "$PROOF/$n.png" ] && python3 -c "
from PIL import Image; im=Image.open('$PROOF/$n.png'); im.thumbnail((480,480)); im.save('$PROOF/sweep/$n.png')" 2>/dev/null || true
    rm -f "$PROOF/$n-client.log" "$PROOF/$n.png" "$PROOF/$n-host.log"
    pkill -9 -f "^$PROOT " 2>/dev/null || true; sleep 1   # nothing of this rung (dbus, Xwayland, helpers) lingers into the next
    if [ "$r" = OK ]; then final="OK on $rid"; reason=""; break; fi
    reason="$rid: $r"
  done < <(python3 scripts/renderer-ladder.py "$app" "${SWEEP_GPU:-1}" "$CAT")
  status=PASS; [[ "$final" == OK* ]] || status=FAIL
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$app" "$name" "$kind" "${tried[*]}" "$status" "${final/OK on /works on }${reason:+ — last: $reason} (install ${INST}s)" >> "$TSV"
  cleanup_app "$app"
  df -h / | tail -1 | awk '{print "disk free: "$4}'
done
stamp "sweep shard $SHARD done"
column -t -s $'\t' "$TSV" || cat "$TSV"
exit 0
