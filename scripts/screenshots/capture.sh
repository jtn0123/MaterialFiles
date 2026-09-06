#!/bin/bash
# capture.sh <serial> <outdir> <clip.mp4>
# Screenshots of the inset-sensitive screens of an installed debug build, for compare.py.
# Drives the UI through ui.py (uiautomator dumps), so it needs python3 on the host.
set -u
SER=$1; OUT=$2; CLIP=$3; mkdir -p "$OUT"; S=$(cd "$(dirname "$0")" && pwd)
PKG=me.zhanghai.android.files; DIR=/storage/emulated/0/Movies/ScreenshotTest
u() { python3 "$S/ui.py" "$SER" "$@" >/dev/null || echo "ui: $* not found"; }
e() { adb -s "$SER" "$@"; }
shot() { sleep "${2:-1.5}"; e exec-out screencap -p > "$OUT/$1.png"; }
open() { e shell "am force-stop $PKG; am start -W -n $PKG/.filelist.FileListActivity -a android.intent.action.VIEW -d file://$DIR -t inode/directory" >/dev/null; sleep 3; }
rot() { e shell "settings put system accelerometer_rotation 0; settings put system user_rotation $1"; sleep 4; }

# Test files: two copies of the clip, a generated image, a text file.
python3 - "$OUT/shot.png" <<'PY'
import struct, sys, zlib
w, h = 320, 240
rows = b"".join(b"\x00" + bytes(v for x in range(w) for v in (x * 255 // w, y * 255 // h, 128)) for y in range(h))
def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(png)
PY
e shell "mkdir -p $DIR" >/dev/null
e push "$CLIP" "$DIR/Clip A.mp4" >/dev/null; e push "$CLIP" "$DIR/Clip B.mp4" >/dev/null
e push "$OUT/shot.png" "$DIR/shot.png" >/dev/null; rm -f "$OUT/shot.png"
e shell "echo 'Some notes for the screenshot run.' > '$DIR/notes.txt'"
e shell "appops set $PKG MANAGE_EXTERNAL_STORAGE allow; pm grant $PKG android.permission.POST_NOTIFICATIONS" >/dev/null 2>&1
e shell "settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0" >/dev/null

rot 0; open; open; shot list
u tap-desc "Open navigation drawer"; shot drawer; e shell input keyevent BACK; sleep 1
rot 1; shot list-land 2.5; u tap-desc "Open navigation drawer"; shot drawer-land; e shell input keyevent BACK; sleep 1; rot 0; sleep 2
u long-text "Clip A.mp4"; shot selection; e shell input keyevent BACK; sleep 1
u tap-text "Clip B.mp4"; sleep 4; e shell input tap 540 1200; shot video 0.6; rot 1; sleep 2; e shell input tap 1200 540; shot video-land 0.6; rot 0; sleep 1.5; e shell input keyevent BACK; sleep 1
u tap-text "shot.png"; sleep 3; e shell input tap 540 1200; shot image 0.6; rot 1; sleep 2; e shell input tap 1200 540; shot image-land 0.6; rot 0; sleep 1.5; e shell input keyevent BACK; sleep 1
u tap-desc "Open navigation drawer"; sleep 1.5; u tap-text "Settings"; shot settings 2; e shell input keyevent BACK; sleep 1
u tap-desc "Open navigation drawer"; sleep 1.5; u tap-text "About"; shot about 2; e shell input keyevent BACK; sleep 1
u tap-desc "Open navigation drawer"; sleep 1.5; u tap-text "FTP server"; shot ftp 2; e shell input keyevent BACK; sleep 1
rot 0; e shell "rm -r $DIR" >/dev/null
ls "$OUT"
