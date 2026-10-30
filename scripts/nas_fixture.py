import logging
import shutil
import sys
from pathlib import Path

from impacket import smbserver
from impacket.ntlm import compute_lmhash, compute_nthash


MOVIE_SIZE = 60 * 1024 * 1024


def sparse_file(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as stream:
        stream.truncate(MOVIE_SIZE)


def playable_movie(path: Path) -> None:
    sample = Path(__file__).resolve().parents[1] / "CineVaultTests" / "Fixtures" / "playback.mp4"
    path.parent.mkdir(parents=True, exist_ok=True)
    with sample.open("rb") as source, path.open("wb") as destination:
        shutil.copyfileobj(source, destination)
        free_size = MOVIE_SIZE - destination.tell()
        if free_size < 8:
            raise ValueError("The playback sample is too large for the fixture.")
        destination.write(free_size.to_bytes(4, byteorder="big") + b"free")
        destination.truncate(MOVIE_SIZE)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: nas_fixture.py <fixture directory>")

    root = Path(sys.argv[1]).resolve()
    feature = root / "Movies" / "Feature (2020)"
    playable_movie(feature / "Feature (2020).mp4")
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
