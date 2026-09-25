import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
TOOLS = '{http://schemas.android.com/tools}'

class LintB714Tests(unittest.TestCase):
    def test_unused_inventory_permission_and_global_suppression_are_absent(self):
        manifest = ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
        found = [p for p in manifest.findall('uses-permission') if p.get(ANDROID+'name')=='android.permission.QUERY_ALL_PACKAGES']
        self.assertEqual(found, [])
        self.assertNotIn('all', manifest.get(TOOLS+'ignore', '').split(','))

    def test_reports_and_failed_lint_are_not_silently_discarded(self):
        workflow=(ROOT/'.github/workflows/build-apk.yml').read_text()
        self.assertIn('tools/report_lint.py',workflow)
        self.assertIn('dist/lint-reports',workflow)
        self.assertIn('Enforce Release lint result',workflow)
        self.assertLess(workflow.index('Upload complete lint reports'),workflow.index('Enforce Release lint result'))
        self.assertLess(workflow.index('Enforce Release lint result'),workflow.index('Prepare APK'))
        build=(ROOT/'app/build.gradle').read_text()
        self.assertIn('abortOnError = true',build)
        self.assertIn('textReport = true',build)
        self.assertIn('xmlReport = true',build)
        self.assertIn('htmlReport = true',build)
        self.assertNotIn('ignoreWarnings true',build)

    def reporter(self):
        path=ROOT/'tools/report_lint.py'
        self.assertTrue(path.is_file(),'complete lint report collector missing')
        spec=importlib.util.spec_from_file_location('report_lint',path)
        m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
        return m

    def fixture(self,root,xml):
        p=root/'app/build/reports/lint-results-release.xml';p.parent.mkdir(parents=True);p.write_text(xml)
        (p.parent/'lint-results-release.txt').write_text('all unabridged diagnostics\n')
        (p.parent/'lint-results-release.html').write_text('<html>full report</html>')
        (root/'dist').mkdir();(root/'dist/lint-release.log').write_text('gradle raw log\n')

    def test_all_twelve_warnings_are_exported_without_stacktrace_truncation(self):
        m=self.reporter()
        with tempfile.TemporaryDirectory() as d:
            r=Path(d)
            issues=''.join('<issue id="Warn%s" severity="Warning" message="warning %s"><location file="src/Test.java" line="%s"/></issue>'%(i,i,i+1) for i in range(12))
            self.fixture(r,'<issues>'+issues+'</issues>')
            result=m.collect(r,'success')
            self.assertEqual(result['warnings'],12);self.assertTrue(result['passed'])
            summary=(r/'dist/lint-summary.md').read_text()
            for i in range(12): self.assertIn('Warn'+str(i),summary)
            self.assertEqual((r/'dist/lint-reports/lint-results-release.txt').read_text(),'all unabridged diagnostics\n')
            self.assertTrue((r/'dist/lint-reports/lint-results-release.html').is_file())

    def test_error_report_does_not_become_green_even_if_gradle_status_says_success(self):
        m=self.reporter()
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.fixture(r,'<issues><issue id="NewApi" severity="Error" message="API not supported"/></issues>')
            self.assertFalse(m.collect(r,'success')['passed'])

    def test_missing_or_malformed_report_is_unknown_not_zero_errors(self):
        m=self.reporter()
        for text in (None,'<issues','<unrelated/>'):
            with self.subTest(text=text),tempfile.TemporaryDirectory() as d:
                r=Path(d)
                if text is not None:self.fixture(r,text)
                result=m.collect(r,'success')
                self.assertFalse(result['passed']);self.assertIsNone(result['errors'])

    def test_gradle_crash_cannot_pass_with_stale_clean_xml(self):
        m=self.reporter()
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.fixture(r,'<issues/>')
            self.assertFalse(m.collect(r,'failure')['passed'])

    def test_only_known_lint_artifacts_are_collected(self):
        m=self.reporter()
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.fixture(r,'<issues/>');(r/'dev-signing.jks').write_bytes(b'secret')
            (r/'app/build/reports/other.txt').write_text('not a lint artifact')
            self.assertTrue(m.collect(r,'success')['passed'])
            names=[p.name for p in (r/'dist/lint-reports').rglob('*') if p.is_file()]
            self.assertNotIn('dev-signing.jks',names);self.assertNotIn('other.txt',names)

    def test_cli_upload_phase_succeeds_but_publishing_gate_rejects_errors(self):
        m=self.reporter()
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.fixture(r,'<issues><issue id="FatalApi" severity="Fatal" message="unsupported"/></issues>')
            command=[sys.executable,str(ROOT/'tools/report_lint.py'),'--project',str(r),'--outcome','failure']
            exported=subprocess.run(command,capture_output=True,text=True)
            self.assertEqual(exported.returncode,0,exported.stderr)
            self.assertTrue((r/'dist/lint-reports/lint-results-release.xml').is_file())
            checked=subprocess.run(command+['--check'],capture_output=True,text=True)
            self.assertEqual(checked.returncode,1)

    def test_cli_warnings_only_does_not_block_apk(self):
        self.reporter()
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.fixture(r,'<issues><issue id="OneWarning" severity="Warning" message="review"/></issues>')
            result=subprocess.run([sys.executable,str(ROOT/'tools/report_lint.py'),'--project',str(r),'--outcome','success','--check'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)

    def test_clean_removes_old_lint_only_and_missing_output_fails_gate(self):
        m=self.reporter()
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.fixture(r,'<issues/>');m.collect(r,'success')
            (r/'dev-signing.jks').write_bytes(b'keep')
            (r/'app/build/reports/not-lint.txt').write_bytes(b'keep')
            intermediate=r/'app/build/intermediates/lint_intermediate_text_report/release/lintReportRelease/lint-results-release.txt'
            intermediate.parent.mkdir(parents=True);intermediate.write_text('old report')
            m.clean(r)
            self.assertEqual((r/'dev-signing.jks').read_bytes(),b'keep')
            self.assertEqual((r/'app/build/reports/not-lint.txt').read_bytes(),b'keep')
            self.assertFalse(intermediate.exists())
            self.assertFalse(m.collect(r,'success')['passed'])

    def test_unknown_severity_never_becomes_zero_errors(self):
        m=self.reporter()
        with tempfile.TemporaryDirectory() as d:
            r=Path(d);self.fixture(r,'<issues><issue id="ChangedFormat" severity="Unexpected"/></issues>')
            result=m.collect(r,'success')
            self.assertFalse(result['passed']);self.assertIsNone(result['errors'])

    def test_skipped_or_cancelled_lint_never_passes_an_existing_clean_report(self):
        m=self.reporter()
        for outcome in ('cancelled','skipped'):
            with self.subTest(outcome=outcome),tempfile.TemporaryDirectory() as d:
                r=Path(d);self.fixture(r,'<issues/>')
                self.assertFalse(m.collect(r,outcome)['passed'])
