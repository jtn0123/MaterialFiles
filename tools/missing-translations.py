#!/usr/bin/env python3
"""List translatable resources that are missing from each locale.

Compares every translatable <string>, <plurals> and <string-array> in
app/src/main/res/values/*.xml (skipping donottranslate*.xml and entries with
translatable="false") against the values-<locale>/ folders that already hold a
strings.xml. A key counts as present if any file in the locale folder defines
it.

Usage:
    python3 tools/missing-translations.py            # count + keys per locale
    python3 tools/missing-translations.py --union    # union of missing keys
    python3 tools/missing-translations.py --json     # machine-readable output

Exit status is 1 when anything is missing, 0 otherwise.
"""
import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
TAGS = ("string", "plurals", "string-array")


def translatable(path):
    """Return {name: tag} of the translatable resources in a values file."""
    root = ET.parse(path).getroot()
    if root.get("translatable") == "false":
        return {}
    result = {}
    for element in root:
        if element.tag not in TAGS:
            continue
        if element.get("translatable") == "false":
            continue
        result[element.get("name")] = element.tag
    return result


def default_resources():
    """Return {file name: [resource names in file order]}."""
    files = {}
    for path in sorted((RES / "values").glob("*.xml")):
        if path.name.startswith("donottranslate"):
            continue
        names = list(translatable(path))
        if names:
            files[path.name] = names
    return files


def locale_dirs():
    return [d for d in sorted(RES.glob("values-*")) if (d / "strings.xml").exists()]


def missing_per_locale():
    """Return {locale: [(default file name, resource name), ...]}."""
    defaults = default_resources()
    result = {}
    for directory in locale_dirs():
        present = set()
        for path in directory.glob("*.xml"):
            present |= set(translatable(path))
        result[directory.name[len("values-"):]] = [
            (file_name, name)
            for file_name, names in defaults.items()
            for name in names
            if name not in present
        ]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--union", action="store_true", help="print the union of missing keys")
    parser.add_argument("--json", action="store_true", help="print JSON")
    args = parser.parse_args()
    missing = missing_per_locale()
    if args.json:
        print(json.dumps({k: [f"{f}:{n}" for f, n in v] for k, v in missing.items()}, indent=2))
    elif args.union:
        seen = []
        for entries in missing.values():
            for entry in entries:
                if entry not in seen:
                    seen.append(entry)
        for file_name, name in seen:
            print(f"{file_name}:{name}")
    else:
        for locale, entries in missing.items():
            print(f"{locale}: {len(entries)} missing")
            for file_name, name in entries:
                print(f"    {file_name}:{name}")
    return 1 if any(missing.values()) else 0


if __name__ == "__main__":
    sys.exit(main())
