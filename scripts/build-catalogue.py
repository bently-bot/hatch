#!/usr/bin/env python3
"""Build catalogue/catalogue.json: curated entries + every desktop app in Debian 13 (trixie) AppStream/DEP-11.
Also extracts 128px icons for featured Linux apps into app/src/main/assets/icons/<id>.png (offline store).
usage: build-catalogue.py [--arch arm64] [--cache /tmp/dep11]"""
import argparse, gzip, html, io, json, os, re, tarfile, urllib.request
import yaml

ap = argparse.ArgumentParser()
ap.add_argument("--arch", default="arm64")
ap.add_argument("--cache", default="/tmp/dep11")
ap.add_argument("--root", default=os.path.join(os.path.dirname(__file__), ".."))
a = ap.parse_args()
ROOT = os.path.abspath(a.root)
BASE = "https://deb.debian.org/debian/dists/trixie/main/dep11"
MEDIA = "https://appstream.debian.org/media/trixie/"
os.makedirs(a.cache, exist_ok=True)

def fetch(name):
    p = os.path.join(a.cache, name)
    if not os.path.exists(p):
        urllib.request.urlretrieve(f"{BASE}/{name}", p)
    return p

CATS = [  # (our id, name, freedesktop main categories in priority order)
    ("office", "Office", ["Office"]), ("graphics", "Graphics", ["Graphics"]),
    ("internet", "Internet", ["Network", "WebBrowser"]), ("media", "Music & Video", ["AudioVideo", "Audio", "Video"]),
    ("games", "Games", ["Game"]), ("dev", "Developer", ["Development"]), ("education", "Education", ["Education"]),
    ("science", "Science", ["Science"]), ("editors", "Editors", ["TextEditor"]), ("system", "System", ["System", "Settings"]),
    ("utilities", "Utilities", ["Utility"]),
]
def category(fd):
    fd = fd or []
    if "TextEditor" in fd: return "editors"
    for cid, _, keys in CATS:
        if any(k in fd for k in keys): return cid
    return "utilities"

def text(d, key):
    v = d.get(key) or {}
    return v.get("C") or v.get("en") or next(iter(v.values()), "") if isinstance(v, dict) else str(v)

def plain(htmltext, n=700):
    t = re.sub(r"<li>", "• ", htmltext or ""); t = re.sub(r"</p>|</li>", "\n", t); t = re.sub(r"<[^>]+>", "", t)
    t = html.unescape(re.sub(r"[ \t]+", " ", t)).strip()
    t = re.sub(r"\n\s*\n+", "\n\n", t)
    return (t[:n].rsplit(" ", 1)[0] + "…") if len(t) > n else t

docs = yaml.load_all(gzip.open(fetch(f"Components-{a.arch}.yml.gz"), "rt"), Loader=yaml.CSafeLoader)
next(docs)
comps = []
for d in docs:
    if d.get("Type") != "desktop-application": continue
    desk = (d.get("Launchable") or {}).get("desktop-id") or []
    if not desk or not d.get("Package"): continue
    if "NoDisplay" in str(d.get("Categories")): continue
    comps.append(d)
per_pkg = {}
for d in comps: per_pkg[d["Package"]] = per_pkg.get(d["Package"], 0) + 1

curated = json.load(open(os.path.join(ROOT, "catalogue/curated.json")))
cur_by_id = {x["id"]: x for x in curated["apps"]}
verified = set(curated.get("verified", []))
featured = curated.get("featured", [])

apps = {}
icon_names = {}
for d in comps:
    pkg = d["Package"]; did = d["Launchable"]["desktop-id"][0]
    aid = pkg if per_pkg[pkg] == 1 else re.sub(r"[^a-z0-9_.-]", "-", did.removesuffix(".desktop").lower())
    icon = d.get("Icon") or {}
    remote = sorted(icon.get("remote") or [], key=lambda r: -r.get("width", 0))
    cached = [c for c in icon.get("cached") or [] if c.get("width") == 128]
    if cached: icon_names[aid] = cached[0]["name"]
    shots = []
    for s in d.get("Screenshots") or []:
        th = sorted(s.get("thumbnails") or [], key=lambda t: -t.get("width", 0))
        url = (th[0]["url"] if th else (s.get("source-image") or {}).get("url"))
        if url: shots.append(MEDIA + url)
    apps[aid] = {
        "id": aid, "name": text(d, "Name"), "summary": text(d, "Summary"), "description": plain(text(d, "Description")),
        "category": category(d.get("Categories")), "source": "debian", "packages": [pkg],
        "command": ["hatch-desktop", did], "desktopId": did,
        "iconUrl": (MEDIA + remote[0]["url"]) if remote else "", "screenshots": shots[:4],
        "license": d.get("ProjectLicense", ""),
    }

# Curated entries override/extend generated ones (keep their tested commands & flags).
for cid, c in cur_by_id.items():
    base = apps.get(cid) or next((x for x in apps.values() if c.get("packages") and x["packages"] == c["packages"][:1]), None) or {}
    merged = {**base, **{k: v for k, v in c.items() if v not in (None, "", [])}}
    if base and "desktopId" in base and c.get("command"): merged["command"] = c["command"]
    if base: merged["category"] = base["category"]   # AppStream categories beat our old hand-picked ones
    if base and base["id"] != cid: apps.pop(base["id"], None)
    apps[cid] = merged

for x in apps.values():
    x["featured"] = x["id"] in featured
    x["verified"] = x["id"] in verified
order = {k: i for i, k in enumerate(featured)}
out = sorted(apps.values(), key=lambda x: (order.get(x["id"], 10_000), x["name"].lower()))

cats = [{"id": cid, "name": n} for cid, n, _ in CATS] + [{"id": "windows", "name": "Windows"}]
cat = {"version": 2, "updated": curated.get("updated", ""), "categories": cats, "apps": out}
cat["apps"] = [{k: v for k, v in a.items() if v is not None and v != "" and v != [] and v is not False or k in ("id","name","summary","command","packages","category")} for a in cat["apps"]]
s = json.dumps(cat, ensure_ascii=False, separators=(",", ":"))
for p in ("catalogue/catalogue.json", "app/src/main/assets/catalogue.json", "app/src/test/resources/catalogue.json"):
    open(os.path.join(ROOT, p), "w").write(s)

# Offline icons for featured apps
icons_dir = os.path.join(ROOT, "app/src/main/assets/icons"); os.makedirs(icons_dir, exist_ok=True)
want = {icon_names[i]: i for i in featured if i in icon_names}
with tarfile.open(fetch("icons-128x128.tar.gz")) as tf:
    for m in tf:
        if m.name in want:
            open(os.path.join(icons_dir, want[m.name] + ".png"), "wb").write(tf.extractfile(m).read())
print(f"{len(out)} apps ({sum(1 for x in out if x['featured'])} featured), {len(os.listdir(icons_dir))} bundled icons, {len(s)//1024} KB")
