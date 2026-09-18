#!/usr/bin/env python3
"""Format only named Kotlin files and retire their baseline entries after a clean check."""
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
if len(sys.argv) < 2:
    sys.exit('Usage: tools/format-files.py app/src/.../File.kt [...]')
paths = []
for name in sys.argv[1:]:
    path = Path(name).resolve()
    if ',' in str(path) or not path.is_file() or path.suffix != '.kt' or not path.is_relative_to(root / 'app/src'):
        sys.exit(f'Not a Kotlin source file under app/src: {name}')
    paths.append(path.relative_to(root / 'app').as_posix())
selection = '-PformatFiles=' + ','.join(paths)
snapshots = {p: p.read_bytes() for p in (root / 'app/src').rglob('*.kt')}
selected = {root / 'app' / p for p in paths}
try:
    subprocess.run([str(root / 'gradlew'), ':app:formatSelected', selection], cwd=root, check=True)
finally:
    unexpected = []
    for path, original in snapshots.items():
        if path not in selected and path.read_bytes() != original:
            path.write_bytes(original)
            unexpected.append(str(path))
    if unexpected:
        sys.exit('Formatter escaped its scope; restored unselected files: ' + ', '.join(unexpected))
baseline = root / 'app/ktlint-baseline.xml'
before = baseline.read_text()
after = before
for path in paths:
    after = re.sub(r'    <file name="' + re.escape(path) + r'">\n.*?    </file>\n', '', after, flags=re.S)
baseline.write_text(after)
try:
    subprocess.run([str(root / 'gradlew'), ':app:checkSelected', selection], cwd=root, check=True)
except BaseException:
    baseline.write_text(before)
    raise
print(f'Formatted and checked {len(paths)} selected files. Run the full gate without -PformatFiles.')
