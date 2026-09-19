#!/usr/bin/env python3
"""Collect real JaCoCo XML reports and reject missing/empty CI coverage inputs."""
import argparse
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = ('app-unit.xml', 'dav4jvm-unit.xml', 'app-android-35.xml', 'app-android-36.xml')


def validate(path):
    report = ET.parse(path).getroot()
    if report.tag != 'report' or not report.findall('./package/sourcefile/line'):
        raise ValueError(f'{path}: expected a JaCoCo report with source-line coverage')
    counter = report.find("./counter[@type='LINE']")
    if counter is None or int(counter.get('covered', '0')) == 0:
        raise ValueError(f'{path}: no covered source lines; check coverage instrumentation')
    covered = int(counter.get('covered'))
    total = covered + int(counter.get('missed'))
    print(f'{path.name}: {covered}/{total} lines covered ({100 * covered / total:.1f}%)')


def collect(source, name):
    validate(source)
    output = ROOT / 'coverage' / name
    output.parent.mkdir(exist_ok=True)
    shutil.copyfile(source, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('unit', 'android', 'verify'))
    parser.add_argument('--api-level', choices=('35', '36'))
    args = parser.parse_args()
    if args.mode == 'unit':
        collect(ROOT / 'app/build/reports/coverage/test/debug/report.xml', 'app-unit.xml')
        collect(ROOT / 'dav4jvm/build/reports/jacoco/test/jacocoTestReport.xml', 'dav4jvm-unit.xml')
    elif args.mode == 'android':
        if not args.api_level:
            parser.error('android requires --api-level')
        collect(ROOT / 'app/build/reports/coverage/androidTest/debug/connected/report.xml',
                f'app-android-{args.api_level}.xml')
    else:
        for name in EXPECTED:
            validate(ROOT / 'coverage' / name)


if __name__ == '__main__':
    main()
