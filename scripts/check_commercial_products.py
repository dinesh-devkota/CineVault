import argparse
import plistlib
import sys
from xml.parsers.expat import ExpatError


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plist")
    parser.add_argument("bundle_id")
    parser.add_argument("monthly_id")
    parser.add_argument("lifetime_id")
    args = parser.parse_args()

    try:
        with open(args.plist, "rb") as file:
            info = plistlib.load(file)
    except (OSError, plistlib.InvalidFileException, ExpatError, ValueError) as error:
        print(f"Could not read the built app's Info.plist: {error}", file=sys.stderr)
        return 1

    expected = {
        "CFBundleIdentifier": args.bundle_id,
        "CineVaultMonthlyProductID": args.monthly_id,
        "CineVaultLifetimeProductID": args.lifetime_id,
    }
    if not isinstance(info, dict) or any(info.get(key) != value for key, value in expected.items()):
        print("The built commercial app has unexpected bundle or purchase product IDs.", file=sys.stderr)
        return 1
    print("Commercial bundle and purchase product IDs match the build configuration.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
