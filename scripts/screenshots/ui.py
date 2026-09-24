#!/usr/bin/env python3
"""ui.py <serial> dump | tap-text T | tap-desc D | long-text T | top"""
import subprocess, sys, re, xml.etree.ElementTree as ET
serial, cmd = sys.argv[1], sys.argv[2]
def adb(*a, **k): return subprocess.run(["adb", "-s", serial, *a], capture_output=True, **k)
def tree():
    out = adb("exec-out", "uiautomator", "dump", "/dev/stdout").stdout
    out = out[out.index(b"<?xml"):] if b"<?xml" in out else out
    return ET.fromstring(out.decode(errors="replace").split("UI hierchary")[0].strip())
def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2
def find(attr, value):
    for n in tree().iter("node"):
        if n.get(attr) == value: return n
    print(f"NOT FOUND {attr}={value!r}"); sys.exit(1)
if cmd == "dump":
    items = set()
    for n in tree().iter("node"):
        for a in ("text", "content-desc"):
            if n.get(a): items.add(n.get(a))
        if n.get("resource-id", "").startswith("me."): items.add("id=" + n.get("resource-id").split("/")[1])
    print(" | ".join(sorted(items)))
elif cmd == "top":
    out = adb("shell", "dumpsys", "activity", "activities").stdout.decode()
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ u0 (\S+)", out); print(m.group(1) if m else "?")
elif cmd == "tap-id":
    idx = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    nodes = [n for n in tree().iter("node") if n.get("resource-id", "").endswith("/" + sys.argv[3])]
    if len(nodes) <= idx: print(f"NOT FOUND id={sys.argv[3]} #{idx}"); sys.exit(1)
    x, y = center(nodes[idx]); adb("shell", "input", "tap", str(x), str(y)); print(f"tap-id {sys.argv[3]}#{idx} at {x},{y}")
else:
    attr = "text" if cmd.endswith("text") else "content-desc"
    x, y = center(find(attr, sys.argv[3]))
    if cmd.startswith("long"): adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "1200")
    else: adb("shell", "input", "tap", str(x), str(y))
    print(f"{cmd} {sys.argv[3]!r} at {x},{y}")
