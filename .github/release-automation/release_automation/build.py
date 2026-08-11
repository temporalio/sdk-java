import base64
import binascii
import gzip
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from collections.abc import Iterable, Mapping, Sequence
from typing import Any

BUILD_IMAGE = "eclipse-temurin:17-jdk@sha256:91b6210cce02091f6f0798a83ec51aa223828242c5a21a85793bb8c28dc891c4"


def tool(command: Sequence[str], *, data: bytes | None = None, quiet: bool = False) -> None:
    """Run a build tool without forwarding release credentials."""
    allowed = {"PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "DOCKER_HOST"}
    subprocess.run(
        command,
        check=True,
        input=data,
        env={key: value for key, value in os.environ.items() if key in allowed},
        stdout=subprocess.DEVNULL if quiet else None,
        stderr=subprocess.DEVNULL if quiet else None,
    )


def unsigned(source: pathlib.Path, work: pathlib.Path, version: str, commit: str) -> pathlib.Path:
    """Build unsigned Maven files in a credential-free container.

    Merged source is trusted to define the release, but the Gradle process still does
    not need signing material. The container keeps those credentials unavailable while
    using the reviewed publishing hooks from the same immutable commit.
    """
    candidate, repository = work / "source", work / "repository"
    shutil.copytree(source, candidate, symlinks=True)
    repository.mkdir()
    command = "docker run --rm --pull=missing --network bridge --cap-drop ALL --security-opt no-new-privileges".split()
    command += "--env HOME=/tmp --env GRADLE_USER_HOME=/tmp/gradle".split()
    command += ["--user", f"{os.getuid()}:{os.getgid()}", "-w", "/workspace"]
    command += ["-v", f"{candidate}:/workspace", "-v", f"{repository}:/payload"]
    command += [BUILD_IMAGE, "./gradlew", "--no-daemon", "-Dmaven.repo.local=/payload"]
    command += [f"-PreleaseVersion={version}", f"-PreleaseCommit={commit}", "publishToMavenLocal"]
    tool(command)
    return repository / "io" / "temporal"


def sign(root: pathlib.Path, home: pathlib.Path, env: Mapping[str, str]) -> None:
    """Sign Maven payload files and create required legacy checksums."""
    try:
        key = base64.b64decode(env["JAR_SIGNING_KEY"], validate=True)
    except (KeyError, binascii.Error) as error:
        raise ValueError("The signing key is missing or invalid.") from error
    key_file = home.parent / "key"
    key_file.write_bytes(key)
    key_file.chmod(0o600)
    home.mkdir(mode=0o700)
    tool(["gpg", "--batch", "--homedir", str(home), "--import", str(key_file)], quiet=True)
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.is_symlink() or path.suffix not in {".jar", ".pom", ".module"}:
            continue
        command = "gpg --batch --yes --pinentry-mode loopback --passphrase-fd 0".split()
        command += ["--homedir", str(home), "--local-user", env["JAR_SIGNING_KEY_ID"]]
        command += ["--armor", "--detach-sign", "--output", f"{path}.asc", str(path)]
        tool(command, data=f"{env['JAR_SIGNING_KEY_PASSWORD']}\n".encode())
        content = path.read_bytes()
        pathlib.Path(f"{path}.md5").write_text(hashlib.md5(content, usedforsecurity=False).hexdigest() + "\n")
        pathlib.Path(f"{path}.sha1").write_text(hashlib.sha1(content, usedforsecurity=False).hexdigest() + "\n")


def validate_maven(
    root: pathlib.Path, manifest: pathlib.Path, policy: Iterable[str], candidate: Any, exact: bool
) -> list[tuple[str, str, int]]:
    """Validate paths, bytes, coordinates, and POM identity in a Maven payload."""
    root, approved, records = root.resolve(), set(policy), []
    for line in manifest.read_text().splitlines():
        relative, checksum, size_text = line.split("\t")
        parts = pathlib.PurePosixPath(relative).parts
        if len(parts) != 5 or parts[:2] != ("io", "temporal"):
            raise ValueError("Maven path is outside policy.")
        artifact, version, filename = parts[2:]
        suffix = r"(?:-(?:sources|javadoc))?\.(?:jar|pom|module)(?:\.(?:asc|md5|sha1))?"
        if (
            artifact not in approved
            or version != candidate.version
            or not re.fullmatch(re.escape(f"{artifact}-{version}") + suffix, filename)
        ):
            raise ValueError("Maven coordinate is outside policy.")
        path, size = (root / relative).resolve(), int(size_text)
        if root not in path.parents or not path.is_file() or path.is_symlink():
            raise ValueError("Maven payload contains an invalid file.")
        data = path.read_bytes()
        if len(data) != size or hashlib.sha256(data).hexdigest() != checksum:
            raise ValueError("Maven payload checksum differs.")
        records.append((relative, checksum, size))
    actual = sorted(path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file())
    if [row[0] for row in records] != actual or len(actual) != len(set(actual)):
        raise ValueError("Maven payload file set differs.")
    for artifact in approved:
        pom = root / "io" / "temporal" / artifact / candidate.version / f"{artifact}-{candidate.version}.pom"
        document = ET.parse(pom).getroot()
        ns = document.tag.partition("}")[0] + "}" if document.tag.startswith("{") else ""
        identity = tuple(document.findtext(f"{ns}{field}", "").strip() for field in ("groupId", "artifactId", "version"))
        identity += (document.findtext(f"{ns}scm/{ns}tag", "").strip().lower(),)
        if identity != ("io.temporal", artifact, candidate.version, candidate.commit):
            raise ValueError(f"Maven POM identity differs for {artifact}.")
        if exact and not any(path.name.endswith(".asc") for path in pom.parent.iterdir()):
            raise ValueError(f"Maven signatures are missing for {artifact}.")
    return records


def archive_maven(bundle: pathlib.Path, output: pathlib.Path) -> None:
    """Create the deterministic tar used as the durable signed payload."""
    paths = [bundle / "manifest.tsv", bundle / "repository"] + sorted(path for path in (bundle / "repository").rglob("*") if path.is_file())
    with tarfile.open(output, "w") as archive:
        for path in paths:
            info = archive.gettarinfo(str(path), path.relative_to(bundle).as_posix())
            info.uid = info.gid = info.mtime = 0
            info.uname = info.gname = ""
            if path.is_file():
                with path.open("rb") as stream:
                    archive.addfile(info, stream)
            else:
                archive.addfile(info)


def unpack_maven(archive: pathlib.Path, output: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path]:
    """Extract only regular files under the expected Maven bundle roots."""
    output, seen = output.resolve(), set()
    with tarfile.open(archive, "r:") as bundle:
        for member in bundle:
            name = member.name.rstrip("/")
            path = pathlib.PurePosixPath(name)
            allowed = name == "manifest.tsv" or name == "repository" or name.startswith("repository/io/temporal/")
            if not name or path.is_absolute() or ".." in path.parts or name in seen or not allowed:
                raise ValueError("Unexpected Maven archive path.")
            seen.add(name)
            target = output.joinpath(*path.parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                source = bundle.extractfile(member)
                if source is None:
                    raise ValueError("Unreadable Maven archive member.")
                with source, target.open("xb") as destination:
                    shutil.copyfileobj(source, destination)
            else:
                raise ValueError("Maven archive links and special files are forbidden.")
    return output / "repository", output / "manifest.tsv"


def maven(env: Mapping[str, str]) -> None:
    """Build, sign, validate, and freeze the exact Maven payload."""
    source = pathlib.Path.cwd()
    output, version, commit = (
        pathlib.Path(env["MAVEN_PAYLOAD_OUTPUT"]),
        env["MAVEN_PAYLOAD_VERSION"],
        env["MAVEN_PAYLOAD_COMMIT"],
    )
    artifacts = json.loads(env["MAVEN_ARTIFACTS_JSON"])
    if subprocess.check_output(["git", "rev-parse", "HEAD^{commit}"], text=True).strip() != commit:
        raise ValueError("The source checkout changed.")
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="sdk-java-maven-") as directory:
        work, bundle = pathlib.Path(directory), pathlib.Path(directory) / "bundle"
        generated = unsigned(source, work / "build", version, commit)
        sign(generated, work / "gnupg", env)
        repository = bundle / "repository" / "io" / "temporal"
        repository.mkdir(parents=True)
        for artifact in artifacts:
            shutil.copytree(generated / artifact / version, repository / artifact / version)
        files = sorted(path for path in (bundle / "repository").rglob("*") if path.is_file())
        manifest = bundle / "manifest.tsv"
        manifest.write_text(
            "".join(
                f"{path.relative_to(bundle / 'repository').as_posix()}\t{hashlib.sha256(path.read_bytes()).hexdigest()}\t{path.stat().st_size}\n"
                for path in files
            )
        )
        candidate = type("Candidate", (), {"version": version, "commit": commit})()
        validate_maven(bundle / "repository", manifest, artifacts, candidate, True)
        archive_maven(bundle, output / "maven-payload.tar")


def native(source: pathlib.Path, output: pathlib.Path, root: str, name: str, windows: bool) -> None:
    """Create a reproducible one-binary native release archive."""
    data = source.read_bytes()
    if windows:
        entry = zipfile.ZipInfo(f"{root}/{name}", (1980, 1, 1, 0, 0, 0))
        entry.compress_type, entry.external_attr = (
            zipfile.ZIP_DEFLATED,
            (stat.S_IFREG | 0o755) << 16,
        )
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr(entry, data)
        return
    tar = io.BytesIO()
    with tarfile.open(fileobj=tar, mode="w", format=tarfile.GNU_FORMAT) as archive:
        directory, binary = tarfile.TarInfo(root), tarfile.TarInfo(f"{root}/{name}")
        directory.type, directory.mode = tarfile.DIRTYPE, 0o755
        binary.size, binary.mode = len(data), 0o755
        for item in (directory, binary):
            item.uid = item.gid = item.mtime = 0
            item.uname = item.gname = ""
        archive.addfile(directory)
        archive.addfile(binary, io.BytesIO(data))
    with (
        output.open("wb") as raw,
        gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed,
    ):
        compressed.write(tar.getvalue())


def main() -> None:
    """Dispatch Maven construction or deterministic native packaging."""
    if sys.argv[1:] == ["maven"]:
        maven(os.environ)
    elif len(sys.argv) == 7 and sys.argv[1] == "native":
        _, _, source, output, root, name, platform = sys.argv
        native(pathlib.Path(source), pathlib.Path(output), root, name, platform == "windows-amd64")
    else:
        raise ValueError("Expected maven or native build command.")


if __name__ == "__main__":
    main()
