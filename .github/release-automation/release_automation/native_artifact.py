import gzip
import io
import stat
import sys
import tarfile
import zipfile
from pathlib import Path


def package(source: Path, output: Path, root: str, name: str, windows: bool) -> None:
    """Create a byte-reproducible native archive with one executable entry.

    Fixed timestamps, ownership, permissions, and member order make two builds of
    identical native bytes produce the same public archive on every runner.
    """
    data = source.read_bytes()
    if windows:
        entry = zipfile.ZipInfo(f"{root}/{name}", (1980, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = (stat.S_IFREG | 0o755) << 16
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr(entry, data)
        return

    tar_bytes = io.BytesIO()
    with tarfile.open(fileobj=tar_bytes, mode="w", format=tarfile.GNU_FORMAT) as archive:
        directory = tarfile.TarInfo(root)
        directory.type, directory.mode = tarfile.DIRTYPE, 0o755
        directory.uid = directory.gid = directory.mtime = 0
        directory.uname = directory.gname = ""
        archive.addfile(directory)
        binary = tarfile.TarInfo(f"{root}/{name}")
        binary.size, binary.mode = len(data), 0o755
        binary.uid = binary.gid = binary.mtime = 0
        binary.uname = binary.gname = ""
        archive.addfile(binary, io.BytesIO(data))
    with (
        output.open("wb") as raw,
        gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed,
    ):
        compressed.write(tar_bytes.getvalue())


def main() -> None:
    """Package the validated shell inputs without embedding Python in the script."""
    source, output, root, name, platform = sys.argv[1:]
    package(Path(source), Path(output), root, name, platform == "windows-amd64")


if __name__ == "__main__":
    main()
