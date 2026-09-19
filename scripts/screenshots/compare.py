#!/usr/bin/env python3
"""compare.py <baseline dir> <current dir> [max percent]

Pixel-diffs every PNG in the baseline against the same name in the current directory. Exits 1
when a screenshot differs by more than the threshold (default 0.5 % of pixels), is a different
size, or is missing. Needs Pillow.
"""
import pathlib
import sys

from PIL import Image, ImageChops

baseline, current = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
max_percent = float(sys.argv[3]) if len(sys.argv) > 3 else 0.5
failed = False
for path in sorted(baseline.glob("*.png")):
    other = current / path.name
    if not other.exists():
        print(f"{path.name}: missing from {current}")
        failed = True
        continue
    a = Image.open(path).convert("RGB")
    b = Image.open(other).convert("RGB")
    if a.size != b.size:
        print(f"{path.name}: size {a.size} vs {b.size}")
        failed = True
        continue
    diff = ImageChops.difference(a, b).convert("L").point(lambda v: 255 if v > 24 else 0)
    changed = sum(1 for v in diff.getdata() if v)
    percent = 100 * changed / (a.size[0] * a.size[1])
    status = "FAIL" if percent > max_percent else "ok"
    print(f"{path.name}: {percent:.2f}% differ, bbox={diff.getbbox()} {status}")
    if percent > max_percent:
        failed = True
        diff.save(current / f"{path.stem}.diff.png")
sys.exit(1 if failed else 0)
