import logging
import sys
from pathlib import Path

from impacket import smbserver
from impacket.ntlm import compute_lmhash, compute_nthash


def sparse_file(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as stream:
        stream.truncate(60 * 1024 * 1024)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: nas_fixture.py <fixture directory>")

    root = Path(sys.argv[1]).resolve()
    feature = root / "Movies" / "Feature (2020)"
    sparse_file(feature / "Feature (2020).mp4")
    (feature / "bytes.bin").write_bytes(b"0123456789")
    sparse_file(root / "Movies" / "Extras" / "Ignored.mp4")
    sparse_file(root / "Movies" / "@eaDir" / "Ignored.mp4")
    (root / "Movies" / "Too Small.mp4").write_bytes(b"small")

    logging.basicConfig(level=logging.INFO)
    server = smbserver.SimpleSMBServer(listenAddress="127.0.0.1", listenPort=1445)
    server.addShare("CINEVAULT", str(root), readOnly="yes")
    server.setSMB2Support(True)
    server.addCredential("cinevault", 0, compute_lmhash("fixture-only"), compute_nthash("fixture-only"))
    server.start()


if __name__ == "__main__":
    main()
