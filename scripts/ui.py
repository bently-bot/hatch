#!/usr/bin/env python3
"""Tiny uiautomator driver for CI.
  ui.py has <text-regex>            exit 0 if a node's text/content-desc matches
  ui.py tap <text-regex> [pkg-re]   tap the centre of the first (clickable ancestor of) match
  ui.py wait <text-regex> <secs> [pkg-re]  poll until match
  ui.py dump                         print visible texts"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

def _raw_dump():
    for _ in range(3):
        out = subprocess.run(["adb", "exec-out", "uiautomator", "dump", "/dev/tty"], capture_output=True, text=True).stdout
        i = out.find("<?xml"); j = out.rfind("</hierarchy>")
        if i >= 0 and j > i:
            return ET.fromstring(out[i:j + len("</hierarchy>")])
        time.sleep(1)
    return None

def dump():
    """Dump the UI, first dismissing emulator noise ("X isn't responding" ANR dialogs) with Wait."""
    for _ in range(3):
        root = _raw_dump()
        if root is None: return None
        texts = [n.get("text", "") or "" for n in root.iter("node")]
        if not any("isn't responding" in t or "keeps stopping" in t for t in texts): return root
        for n in root.iter("node"):
            if (n.get("text") or "") in ("Wait", "Close app", "Close"):
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
                print(f"dismissing system dialog via {n.get('text')!r}", file=sys.stderr)
                subprocess.run(["adb", "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)]); break
        time.sleep(1.5)
    return _raw_dump()

def find(rx, pkg=None):
    root = dump()
    if root is None: return None
    r = re.compile(rx, re.I)
    parents = {c: p for p in root.iter() for c in p}
    # Prefer real labels over text fields: a search box holding "mousepad" must not win over the "Mousepad" result.
    nodes = sorted(root.iter("node"), key=lambda n: "EditText" in (n.get("class") or ""))
    for n in nodes:
        if pkg and not re.search(pkg, n.get("package", "")): continue
        if r.search(n.get("text", "") or "") or r.search(n.get("content-desc", "") or ""):
            m = n
            while m is not None and m.get("clickable") != "true" and m in parents: m = parents[m]
            tgt = m if m is not None and m.get("clickable") == "true" else n
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", tgt.get("bounds")))
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None

cmd = sys.argv[1]
if cmd == "dump":
    root = dump()
    for n in (root.iter("node") if root is not None else []):
        t = n.get("text") or n.get("content-desc")
        if t: print(f'{n.get("package")}: {t!r} {n.get("bounds")}')
elif cmd == "has":
    sys.exit(0 if find(sys.argv[2]) else 1)
elif cmd == "tap":
    p = find(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    if not p: print(f"not found: {sys.argv[2]}", file=sys.stderr); sys.exit(1)
    subprocess.run(["adb", "shell", "input", "tap", str(p[0]), str(p[1])]); print(f"tapped {sys.argv[2]} at {p}")
elif cmd == "wait":
    end = time.time() + float(sys.argv[3])
    while time.time() < end:
        if find(sys.argv[2], sys.argv[4] if len(sys.argv) > 4 else None): print(f"found {sys.argv[2]}"); sys.exit(0)
        time.sleep(3)
    print(f"timeout waiting for {sys.argv[2]}", file=sys.stderr); sys.exit(1)
