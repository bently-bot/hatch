#!/usr/bin/env python3
"""Print hatch-install-app / hatch-launch argv for a catalogue app (mirrors GuestScripts.kt).
usage: catalogue-args.py install|launch <app-id> [catalogue.json]"""
import json, shlex, sys
mode, app_id = sys.argv[1], sys.argv[2]
cat = json.load(open(sys.argv[3] if len(sys.argv) > 3 else "catalogue/catalogue.json"))
a = next(x for x in cat["apps"] if x["id"] == app_id)
if mode == "install":
    argv = ["/usr/local/bin/hatch-install-app"]
    if a.get("packages"): argv += ["--packages", " ".join(a["packages"])]
    r = a.get("repo")
    if r: argv += ["--repo", r["id"], r["keyUrl"], r["uris"], r["suites"], r.get("components", "main")]
    if a.get("wine"): argv.append("--wine")
    if a.get("desktopId"): argv += ["--desktop", a["desktopId"]]
    p = a.get("windowsPayload")
    if p: argv += ["--payload", p["url"], p.get("sha256", ""), p["extractTo"]]
else:
    argv = ["/usr/local/bin/hatch-launch"]
    if a.get("wine"): argv.append("--wine")
    if a.get("x11"): argv.append("--x11")
    argv += ["--"] + a["command"]
print(" ".join(shlex.quote(x) for x in argv))
