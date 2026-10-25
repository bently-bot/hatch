#!/usr/bin/env python3
"""Print hatch-install-app / hatch-launch argv for a catalogue app (mirrors GuestScripts.kt).
usage: catalogue-args.py install|uninstall|launch <app-id> [catalogue.json]
uninstall: hatch-uninstall-app argv as Uninstall.command builds it, assuming no other app is installed
(set KEEP_APPS="id1 id2" for the apps that stay installed)."""
import os
import json, shlex, sys
mode, app_id = sys.argv[1], sys.argv[2]
cat = json.load(open(sys.argv[3] if len(sys.argv) > 3 else "catalogue/catalogue.json"))
a = next(x for x in cat["apps"] if x["id"] == app_id)
if mode == "install" and a.get("flatpak"):
    argv = ["/usr/local/bin/hatch-flatpak", "install", a["flatpak"]]
elif mode == "launch" and a.get("command", [""])[0] == "hatch-flatpak-run":
    argv = a["command"]   # run by the host side from the app's spec (scripts/guest-run.sh, FlatpakSpec.kt)
elif mode == "uninstall":
    # Mirrors Uninstall.command (Kotlin): own record (fallback: catalogue list), keep what the others list
    others = [x for x in cat["apps"] if x["id"] in os.environ.get("KEEP_APPS", "").split() and x["id"] != app_id]
    keep = sorted({p for x in others for p in x.get("packages", [])} |
                  ({"wine", "wine64", "libwine", "fonts-wine"} if any(x.get("wine") for x in others) else set()))
    dirs = [p["extractTo"] for p in ([a["windowsPayload"]] if a.get("windowsPayload") else []) + list((a.get("payloads") or {}).values())]
    dirs += [e["dest"] for e in a.get("extras") or []] + ([a["import"]["dest"]] if a.get("import") else [])
    dirs = [d for d in dirs if d.startswith("/opt/hatch/")]
    if a.get("command", [""])[0] == "hatch-steam": dirs += ["~/.local/share/Steam", "~/.steam"]
    dirs = list(dict.fromkeys(dirs))
    argv = ["/usr/local/bin/hatch-uninstall-app", "--id", a["id"]]
    if a.get("packages"): argv += ["--packages", " ".join(a["packages"])]
    if keep: argv += ["--keep", " ".join(keep)]
    if dirs: argv += ["--dirs", " ".join(dirs)]
elif mode == "install":
    argv = ["/usr/local/bin/hatch-install-app"]
    if os.environ.get("NO_RECORD") != "1": argv += ["--id", a["id"]]   # NO_RECORD=1: install like Hatch 0.2.0
    if a.get("packages"): argv += ["--packages", " ".join(a["packages"])]
    r = a.get("repo")
    if r: argv += ["--repo", r["id"], r["keyUrl"], r["uris"], r["suites"], r.get("components", "main")]
    if a.get("wine"): argv.append("--wine")
    if a.get("desktopId"): argv += ["--desktop", a["desktopId"]]
    p = a.get("windowsPayload")
    if p: argv += ["--payload", p["url"], p.get("sha256", ""), p["extractTo"]]
    for e in a.get("extras") or []: argv += ["--extra", e["url"], e.get("sha256", ""), e["dest"], e.get("member", "")]
else:
    argv = ["/usr/local/bin/hatch-launch"]
    if a.get("wine"): argv.append("--wine")
    if a.get("x11"): argv.append("--x11")
    argv += ["--"] + a["command"]
print(" ".join(shlex.quote(x) for x in argv))
