import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "run_commercial_stage.sh"
BASH = shutil.which("bash")


@unittest.skipUnless(BASH, "bash is required")
class CommercialStageTests(unittest.TestCase):
    def test_success_captures_private_output(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "commercial.log"
            result = subprocess.run(
                [BASH, str(SCRIPT), "Package tests", str(log),
                 BASH, "-c", "printf 'private-success-sentinel\\n'"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0)
            self.assertIn("Package tests succeeded", result.stdout)
            self.assertNotIn("private-success-sentinel", result.stdout + result.stderr)
            self.assertIn("private-success-sentinel", log.read_text())
            if os.name != "nt":
                self.assertEqual(log.stat().st_mode & 0o077, 0)

    def test_failure_reports_exit_code_without_leaking_output(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "commercial.log"
            result = subprocess.run(
                [BASH, str(SCRIPT), "Simulator tests", str(log),
                 BASH, "-c", "printf 'private-failure-sentinel\\n' >&2; exit 7"],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 7)
            self.assertIn("Simulator tests failed (exit 7)", result.stderr)
            self.assertNotIn("private-failure-sentinel", result.stdout + result.stderr)
            self.assertIn("private-failure-sentinel", log.read_text())

    def test_missing_arguments_fail(self):
        result = subprocess.run(
            [BASH, str(SCRIPT)], capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("Usage:", result.stderr)


if __name__ == "__main__":
    unittest.main()
