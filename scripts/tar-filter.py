#!/usr/bin/env python3
"""Copy a tar stream stdin→stdout, dropping the named members (paths without a leading ./ or /)."""
import sys, tarfile
drop = set(sys.argv[1:])
src = tarfile.open(fileobj=sys.stdin.buffer, mode="r|")
dst = tarfile.open(fileobj=sys.stdout.buffer, mode="w|", format=tarfile.PAX_FORMAT)
for m in src:
    if m.name.lstrip("./") in drop: continue
    dst.addfile(m, src.extractfile(m) if m.isreg() else None)
dst.close()
