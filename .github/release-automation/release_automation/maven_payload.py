#!/usr/bin/env python3

import hashlib
import pathlib
import re
import sys
import tarfile
import xml.etree.ElementTree as ET
from collections.abc import Iterable


def validate(
    root: pathlib.Path,
    manifest: pathlib.Path,
    policy: Iterable[str],
    version: str,
    commit: str,
    exact: bool,
) -> None:
    """Validate the exact signed Maven repository tree against fixed sdk-java policy.

    Every manifest path, digest, size, coordinate, classifier, signature/checksum sidecar,
    and POM identity is checked. In build mode the per-artifact file set must also be
    complete, preventing a partially generated payload from becoming durable release data.
    """
    root = root.resolve()
    approved = set(policy)
    records = []
    for line in manifest.read_text().splitlines():
        relative, digest, size = line.split("\t")
        parts = pathlib.PurePosixPath(relative).parts
        if len(parts) != 5 or parts[:2] != ("io", "temporal"):
            raise ValueError("path outside Maven policy")
        artifact, found_version, filename = parts[2:]
        suffix = r"(?:-(?:sources|javadoc))?\.(?:jar|pom|module)(?:\.(?:asc|md5|sha1))?"
        if (
            artifact not in approved
            or found_version != version
            or not re.fullmatch(re.escape(f"{artifact}-{version}") + suffix, filename)
        ):
            raise ValueError("coordinate outside Maven policy")
        path = (root / relative).resolve()
        if root not in path.parents or not path.is_file() or path.is_symlink():
            raise ValueError("invalid Maven file")
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != digest or len(data) != int(size):
            raise ValueError("Maven checksum differs")
        records.append(relative)
    actual = sorted(
        str(path.relative_to(root)).replace("\\", "/") for path in root.rglob("*") if path.is_file()
    )
    if not records or records != sorted(set(records)) or actual != records:
        raise ValueError("Maven file set differs")
    for artifact in approved:
        directory = root / "io" / "temporal" / artifact / version
        pom = directory / f"{artifact}-{version}.pom"
        if exact:
            bases = {pom.name, f"{artifact}-{version}.module"}
            if artifact != "temporal-bom":
                bases |= {
                    f"{artifact}-{version}{suffix}.jar" for suffix in ("", "-sources", "-javadoc")
                }
            expected = bases | {
                base + extension for base in bases for extension in (".asc", ".md5", ".sha1")
            }
            if {path.name for path in directory.iterdir() if path.is_file()} != expected:
                raise ValueError(f"Maven file set differs for {artifact}")
        document = ET.parse(pom).getroot()
        namespace = document.tag.partition("}")[0] + "}" if document.tag.startswith("{") else ""
        identity = tuple(
            document.findtext(f"{namespace}{field}", "").strip()
            for field in ("groupId", "artifactId", "version")
        )
        identity += (document.findtext(f"{namespace}scm/{namespace}tag", "").strip().lower(),)
        if identity != ("io.temporal", artifact, version, commit):
            raise ValueError(f"Maven POM identity differs for {artifact}")


def extract(archive_path: pathlib.Path, output: pathlib.Path) -> None:
    """Extract only the expected regular-file Maven bundle without tar traversal.

    Tar extraction is implemented explicitly instead of using extractall so absolute
    paths, parent traversal, duplicates, links, devices, and other special members are
    rejected before they can touch the publication workspace.
    """
    output = output.resolve()
    seen = set()
    with tarfile.open(archive_path, "r:") as archive:
        for member in archive:
            name = member.name.rstrip("/")
            path = pathlib.PurePosixPath(name)
            allowed = name in {
                "manifest.tsv",
                "repository",
                "repository/io",
                "repository/io/temporal",
            } or name.startswith("repository/io/temporal/")
            if not name or path.is_absolute() or ".." in path.parts or name in seen or not allowed:
                raise ValueError("unexpected archive path")
            seen.add(name)
            target = output.joinpath(*path.parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                source = archive.extractfile(member)
                if source is None:
                    raise ValueError("archive file is unreadable")
                with source, target.open("xb") as destination:
                    destination.write(source.read())
            else:
                raise ValueError("archive links and special files are forbidden")


def main() -> None:
    """Dispatch trusted payload validation or validation-after-safe-extraction."""
    mode, source, root, policy, version, commit = sys.argv[1:]
    root_path = pathlib.Path(root)
    if mode == "extract":
        extract(pathlib.Path(source), root_path.parent)
        manifest = root_path.parent / "manifest.tsv"
    elif mode == "validate":
        manifest = pathlib.Path(source)
    else:
        raise ValueError("expected extract or validate")
    approved = pathlib.Path(policy).read_text().splitlines()
    validate(root_path, manifest, approved, version, commit, mode == "validate")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, ET.ParseError, tarfile.TarError) as error:
        raise SystemExit(error) from None
