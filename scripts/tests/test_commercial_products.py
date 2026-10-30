import plistlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "check_commercial_products.py"


class CommercialProductTests(unittest.TestCase):
    def check(self, info, monthly="example.app.monthly", lifetime="example.app.lifetime"):
        with tempfile.TemporaryDirectory() as directory:
            plist = Path(directory) / "Info.plist"
            with plist.open("wb") as file:
                plistlib.dump(info, file)
            return subprocess.run(
                [sys.executable, str(SCRIPT), str(plist), "example.app", monthly, lifetime],
                capture_output=True, text=True, check=False,
            )

    def test_accepts_the_exact_configured_identifiers(self):
        result = self.check({
            "CFBundleIdentifier": "example.app",
            "CineVaultMonthlyProductID": "example.app.monthly",
            "CineVaultLifetimeProductID": "example.app.lifetime",
        })
        self.assertEqual(result.returncode, 0)

    def test_rejects_missing_or_mismatched_identifiers(self):
        result = self.check({
            "CFBundleIdentifier": "example.app",
            "CineVaultMonthlyProductID": "example.app.monthly",
        })
        self.assertNotEqual(result.returncode, 0)
        result = self.check({
            "CFBundleIdentifier": "other.app",
            "CineVaultMonthlyProductID": "example.app.monthly",
            "CineVaultLifetimeProductID": "example.app.lifetime",
        })
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
