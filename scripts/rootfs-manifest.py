#!/usr/bin/env python3
"""Merge per-arch build JSONs into rootfs-manifest.json for release <tag> (URLs on bently-bot/hatch releases)."""
import json, sys, glob, os, datetime
out, tag = sys.argv[1], sys.argv[2]
images = {}
for f in sorted(glob.glob(os.path.join(os.path.dirname(out) or ".", "hatch-rootfs-*.json"))):
    j = json.load(open(f))
    j["url"] = f"https://github.com/bently-bot/hatch/releases/download/{tag}/{j['file']}"
    images[j.pop("arch")] = j
json.dump({"format": 1, "release": tag, "debian": "13 (trixie)", "created": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
           "images": images}, open(out, "w"), indent=1, sort_keys=True)
print(open(out).read())
