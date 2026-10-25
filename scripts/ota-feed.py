#!/usr/bin/env python3
"""Fake GitHub release feed for Hatch's in-app update tests (never touches the real repo).

  ota-feed.py publish DIR VERSION APK [--bad-sha]   copy APK to DIR/hatch-VERSION.apk + .sha256, write DIR/latest.json
  ota-feed.py serve DIR PORT [BYTES_PER_SEC]        static server; .apk downloads throttled so progress is visible

latest.json is a /releases-style list that also holds a draft, a prerelease and a dev-tag release with the same
assets, so the e2e proves those are ignored. The emulator reaches the host as 10.0.2.2."""
import hashlib, http.server, json, os, shutil, sys, time

def release(tag, base, draft=False, pre=False, body="", files=None):
    v = tag.lstrip("v")
    names = files or [f"hatch-{v}.apk", f"hatch-{v}.apk.sha256"]
    return {"tag_name": tag, "name": f"Hatch {v}", "draft": draft, "prerelease": pre, "body": body,
            "html_url": f"{base}/tag/{tag}", "published_at": "2026-10-25T12:00:00Z",
            "assets": [{"name": n, "size": os.path.getsize(os.path.join(DIR, n)) if os.path.exists(os.path.join(DIR, n)) else 0,
                        "browser_download_url": f"{base}/{n}"} for n in names]}

def publish(ver, apk, bad):
    name = f"hatch-{ver}.apk"; dst = os.path.join(DIR, name)
    if os.path.abspath(apk) != os.path.abspath(dst): shutil.copyfile(apk, dst)
    h = hashlib.sha256(open(dst, "rb").read()).hexdigest()
    if bad: h = ("0" if h[0] != "0" else "1") + h[1:]
    open(dst + ".sha256", "w").write(f"{h}  {name}\n")
    base = "http://10.0.2.2:%s" % os.environ.get("OTA_PORT", "8765")
    notes = (f"## What's new in {ver}\n- **In-app updates**: Hatch updates itself from GitHub Releases\n"
             "- Downloads are checked against their `sha256` and Hatch's signing key\n"
             "- Running Linux apps are closed cleanly first\n\n**Full Changelog**: https://example.invalid")
    rels = [release("v0.9.0", base, draft=True, files=[name, name + ".sha256"]),            # drafts: never
            release("v0.4.0-rc.1", base, pre=True, files=[name, name + ".sha256"]),        # prereleases: never
            release("v0.1.0-dev.abc1234", base, files=[name, name + ".sha256"]),           # CI dev drafts: never
            release(f"v{ver}", base, body=notes)]
    json.dump(rels, open(os.path.join(DIR, "latest.json"), "w"), indent=1)
    print(f"published {ver} ({'BAD' if bad else 'good'} sha {h[:12]}…)")

class Handler(http.server.SimpleHTTPRequestHandler):
    rate = 0
    def copyfile(self, src, dst):
        if not self.path.endswith(".apk") or not self.rate: return super().copyfile(src, dst)
        chunk = 64 * 1024
        while True:
            b = src.read(chunk)
            if not b: break
            dst.write(b); time.sleep(len(b) / self.rate)
    def log_message(self, fmt, *a): sys.stderr.write("feed: " + fmt % a + "\n")

if __name__ == "__main__":
    cmd, DIR = sys.argv[1], sys.argv[2]; os.makedirs(DIR, exist_ok=True)
    if cmd == "publish": publish(sys.argv[3], sys.argv[4], "--bad-sha" in sys.argv)
    elif cmd == "serve":
        Handler.rate = int(sys.argv[4]) if len(sys.argv) > 4 else 0
        os.chdir(DIR)
        http.server.ThreadingHTTPServer(("0.0.0.0", int(sys.argv[3])), Handler).serve_forever()
