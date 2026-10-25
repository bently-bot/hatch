#!/usr/bin/env python3
"""Renderer rungs for a catalogue app (mirrors app/src/main/kotlin/dev/hatch/gpu/Renderers.kt — keep in sync).
usage: renderer-ladder.py <app-id> <gpu 0|1> [catalogue.json]
prints one rung per line: id|gpu(0/1)|x11(0/1/-)|args (shell-quoted)|env (K=V ...)|kind"""
import json, shlex, sys
app_id, gpu = sys.argv[1], sys.argv[2] == "1"
cat = json.load(open(sys.argv[3] if len(sys.argv) > 3 else "catalogue/catalogue.json"))
a = next(x for x in cat["apps"] if x["id"] == app_id)
c = " ".join(a.get("command", [])).lower()
if "hatch-steam" in c: kind = "steam"
elif any(k in c for k in ["/usr/share/code/", "/usr/share/cursor/", "brave", "chromium", "chrome", "electron", "--no-sandbox",
                         "obsidian", "discord", "signal-desktop", "slack", "teams", "spotify", "vivaldi", "microsoft-edge"]): kind = "chromium"
else: kind = "generic"
BASE = ["--no-sandbox", "--disable-gpu-sandbox", "--disable-dev-shm-usage"]
WL = {"ELECTRON_OZONE_PLATFORM_HINT": "wayland"}
sw = {"chromium": ("software", 0, "0", BASE + ["--ozone-platform=wayland", "--disable-gpu", "--disable-gpu-compositing"], WL),
      "steam": ("software", 0, "-", ["-cef-disable-gpu", "-cef-disable-gpu-compositing"], {}),
      "generic": ("software", 0, "-", [], {})}[kind]
if not gpu: rungs = [sw]
elif kind == "chromium":
    rungs = [("gpu-x11", 1, "1", BASE + ["--ozone-platform=x11", "--use-gl=angle", "--use-angle=gl", "--ignore-gpu-blocklist"],
              {"ELECTRON_OZONE_PLATFORM_HINT": "x11", "XDG_SESSION_TYPE": "x11"}),
             ("gpu-wayland", 1, "0", BASE + ["--ozone-platform=wayland", "--use-gl=angle", "--use-angle=vulkan",
              "--enable-features=Vulkan,VulkanFromANGLE,DefaultANGLEVulkan", "--ignore-gpu-blocklist"], WL),
             ("gpu-raster", 1, "0", BASE + ["--ozone-platform=wayland", "--use-gl=angle", "--use-angle=gl",
              "--disable-gpu-compositing", "--ignore-gpu-blocklist"], WL),
             sw]
elif kind == "steam": rungs = [("gpu", 1, "-", [], {}), ("gpu-nocomp", 1, "-", ["-cef-disable-gpu-compositing"], {}), sw]
else: rungs = [("gpu", 1, "-", [], {}), sw]
cmd = a.get("command", [])
for rid, g, x11, args, env in rungs:
    args = [] if a.get("wine") else [x for x in args if x not in cmd]
    print("|".join([rid, str(g), x11, " ".join(shlex.quote(x) for x in args), " ".join(f"{k}={v}" for k, v in env.items()), kind]))
