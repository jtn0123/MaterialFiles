"""Regression checks for the CI coverage-import guard."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('coverage_reports', Path(__file__).with_name('coverage-reports.py'))
coverage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(coverage)


class CoverageReportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'report.xml'

    def test_rejects_test_result_xml(self):
        self.path.write_text('<testsuite tests="100" failures="0"/>')
        with self.assertRaisesRegex(ValueError, 'JaCoCo report'):
            coverage.validate(self.path)

    def test_rejects_report_without_instrumented_hits(self):
        self.write_report(0)
        with self.assertRaisesRegex(ValueError, 'no covered source lines'):
            coverage.validate(self.path)

    def test_accepts_real_source_line_coverage(self):
        self.write_report(2)
        coverage.validate(self.path)

    def test_requires_reports_from_both_android_versions(self):
        root = Path(self.directory.name)
        (root / 'coverage').mkdir()
        self.write_report(2)
        for name in coverage.EXPECTED[:-1]:
            (root / 'coverage' / name).write_bytes(self.path.read_bytes())
        with patch.object(coverage, 'ROOT', root), patch('sys.argv', ['coverage-reports.py', 'verify']):
            with self.assertRaises(FileNotFoundError):
                coverage.main()

    def write_report(self, covered):
        self.path.write_text(
            '<report><package name="app"><sourcefile name="Example.kt">'
            f'<line nr="1" mi="0" ci="{covered}" mb="0" cb="0"/>'
            '</sourcefile></package>'
            f'<counter type="LINE" missed="1" covered="{covered}"/></report>'
        )


if __name__ == '__main__':
    unittest.main()
