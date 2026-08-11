#!/usr/bin/env python3

import base64
import binascii
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET
from collections.abc import Iterable, Mapping, Sequence

BUILD_IMAGE = (
    "eclipse-temurin:17-jdk@sha256:91b6210cce02091f6f0798a83ec51aa223828242c5a21a85793bb8c28dc891c4"
)
REQUIRED_BUILD_VALUES = (
    "JAR_SIGNING_KEY",
    "JAR_SIGNING_KEY_ID",
    "JAR_SIGNING_KEY_PASSWORD",
    "MAVEN_ARTIFACTS_JSON",
    "MAVEN_PAYLOAD_COMMIT",
    "MAVEN_PAYLOAD_OUTPUT",
    "MAVEN_PAYLOAD_VERSION",
    "TRUSTED_AUTOMATION_ROOT",
)


def tool_environment() -> dict[str, str]:
    """Return the small host-runtime environment allowed to reach external tools."""
    allowed = {"PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "DOCKER_HOST"}
    return {key: value for key, value in os.environ.items() if key in allowed}


def run_tool(command: Sequence[str], *, data: bytes | None = None, quiet: bool = False) -> None:
    """Run a fixed external tool without forwarding release credentials in its environment."""
    subprocess.run(
        command,
        check=True,
        env=tool_environment(),
        input=data,
        stdout=subprocess.DEVNULL if quiet else None,
        stderr=subprocess.DEVNULL if quiet else None,
    )


def git_head(root: pathlib.Path) -> str:
    """Return the exact checked-out commit without consulting mutable branch names."""
    return subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "--verify", "HEAD^{commit}"],
        text=True,
        env=tool_environment(),
    ).strip()


def validate_plugin(build_file: pathlib.Path) -> None:
    """Require the source branch to use the sole supported Nexus plugin version."""
    versions = re.findall(
        r"id ['\"]io\.github\.gradle-nexus\.publish-plugin['\"] version ['\"]([^'\"]+)['\"]",
        build_file.read_text(),
    )
    if versions != ["1.3.0"]:
        raise ValueError("sdk-java must use the supported Gradle Nexus plugin 1.3.0")


def build_unsigned(
    source: pathlib.Path,
    trusted: pathlib.Path,
    sandbox: pathlib.Path,
    generated: pathlib.Path,
    version: str,
    commit: str,
) -> None:
    """Build unsigned Maven bytes in a credential-free, capability-reduced container."""
    candidate, gradle_home, candidate_home = (
        sandbox / "source",
        sandbox / "gradle",
        sandbox / "home",
    )
    sandbox.mkdir()
    shutil.copytree(source, candidate, symlinks=True)
    gradle_home.mkdir()
    candidate_home.mkdir()
    for hook in ("versioning.gradle", "publishing.gradle"):
        shutil.copy2(trusted / "gradle" / hook, candidate / "gradle" / hook)
    validate_plugin(candidate / "build.gradle")
    command = (
        "docker run --rm --pull=missing --network bridge --cap-drop ALL "
        "--security-opt no-new-privileges --pids-limit 2048"
    ).split()
    command += ["--user", f"{os.getuid()}:{os.getgid()}", "--workdir", "/workspace"]
    command += "--env HOME=/candidate-home --env GRADLE_USER_HOME=/gradle-home".split()
    for host, container in (
        (candidate, "/workspace"),
        (generated, "/payload"),
        (gradle_home, "/gradle-home"),
        (candidate_home, "/candidate-home"),
    ):
        command += ["--mount", f"type=bind,src={host},dst={container}"]
    command += [BUILD_IMAGE, "./gradlew", "--no-daemon", "-Dmaven.repo.local=/payload"]
    command += [f"-PreleaseVersion={version}", f"-PreleaseCommit={commit}", "publishToMavenLocal"]
    run_tool(command)


def sign(root: pathlib.Path, home: pathlib.Path, env: Mapping[str, str]) -> None:
    """Sign generated Maven files on the trusted host and create required checksums.

    The private key is imported from a mode-0600 temporary file and the password is
    supplied through standard input, keeping both values out of child arguments and
    the isolated candidate build.
    """
    for path in root.rglob("*"):
        if path.suffix in {".asc", ".md5", ".sha1"}:
            path.unlink()
    try:
        key = base64.b64decode(env["JAR_SIGNING_KEY"], validate=True)
    except binascii.Error as error:
        raise ValueError("the protected signing key is not valid base64") from error
    key_file = home.parent / "key"
    key_file.write_bytes(key)
    key_file.chmod(0o600)
    home.mkdir(mode=0o700)
    run_tool(["gpg", "--batch", "--homedir", str(home), "--import", str(key_file)], quiet=True)
    payloads = sorted(
        path
        for path in root.rglob("*")
        if path.is_file() and not path.is_symlink() and path.suffix in {".jar", ".pom", ".module"}
    )
    for payload in payloads:
        command = "gpg --batch --yes".split() + ["--homedir", str(home)]
        command += "--pinentry-mode loopback --passphrase-fd 0".split()
        command += ["--local-user", env["JAR_SIGNING_KEY_ID"]]
        command += ["--armor", "--detach-sign", "--output", f"{payload}.asc", str(payload)]
        run_tool(command, data=f"{env['JAR_SIGNING_KEY_PASSWORD']}\n".encode())
        content = payload.read_bytes()
        pathlib.Path(f"{payload}.md5").write_text(
            hashlib.md5(content, usedforsecurity=False).hexdigest() + "\n"
        )
        pathlib.Path(f"{payload}.sha1").write_text(
            hashlib.sha1(content, usedforsecurity=False).hexdigest() + "\n"
        )


def archive_bundle(bundle: pathlib.Path, output: pathlib.Path) -> None:
    """Create the deterministic uncompressed tar used as the durable Actions artifact."""
    paths = [bundle / "manifest.tsv", bundle / "repository"]
    paths.extend(sorted((bundle / "repository").rglob("*")))
    with tarfile.open(output, "w") as archive:
        for path in paths:
            name = path.relative_to(bundle).as_posix()
            info = archive.gettarinfo(str(path), name)
            info.uid = info.gid = info.mtime = 0
            info.uname = info.gname = ""
            if path.is_file():
                with path.open("rb") as stream:
                    archive.addfile(info, stream)
            else:
                archive.addfile(info)


def build_payload(env: Mapping[str, str], source: pathlib.Path) -> None:
    """Build, sign, validate, and package one immutable sdk-java Maven payload."""
    missing = [name for name in REQUIRED_BUILD_VALUES if not env.get(name)]
    if missing:
        raise ValueError(f"required build value is missing: {missing[0]}")
    commit, version = env["MAVEN_PAYLOAD_COMMIT"], env["MAVEN_PAYLOAD_VERSION"]
    trusted, output = map(
        pathlib.Path, (env["TRUSTED_AUTOMATION_ROOT"], env["MAVEN_PAYLOAD_OUTPUT"])
    )
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or not re.fullmatch(
        r"[0-9]+\.[0-9]+\.[0-9]+(?:-RC[0-9]+)?", version
    ):
        raise ValueError("the immutable Maven identity is invalid")
    if git_head(source) != commit or git_head(trusted) != commit:
        raise ValueError("a source or trusted automation checkout changed")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("the Maven payload output directory is not empty")
    artifacts = json.loads(env["MAVEN_ARTIFACTS_JSON"])
    if (
        not isinstance(artifacts, list)
        or not artifacts
        or len(artifacts) != len(set(artifacts))
        or any(
            not isinstance(item, str) or not re.fullmatch(r"temporal-[a-z0-9-]+", item)
            for item in artifacts
        )
    ):
        raise ValueError("the Maven policy is invalid")
    with tempfile.TemporaryDirectory(prefix="sdk-java-maven-") as directory:
        work = pathlib.Path(directory)
        generated, bundle = work / "generated", work / "bundle"
        repository, manifest = bundle / "repository", bundle / "manifest.tsv"
        generated.mkdir()
        (repository / "io" / "temporal").mkdir(parents=True)
        build_unsigned(source, trusted, work / "sandbox", generated, version, commit)
        sign(generated / "io" / "temporal", work / "gnupg", env)
        for artifact in artifacts:
            source_dir = generated / "io" / "temporal" / artifact / version
            if not source_dir.is_dir():
                raise ValueError(f"Gradle did not generate {artifact}")
            shutil.copytree(
                source_dir, repository / "io" / "temporal" / artifact / version, symlinks=True
            )
        files = sorted(path for path in repository.rglob("*") if path.is_file())
        manifest.write_text(
            "".join(
                f"{path.relative_to(repository).as_posix()}\t{hashlib.sha256(path.read_bytes()).hexdigest()}\t{path.stat().st_size}\n"
                for path in files
            )
        )
        validate(repository, manifest, artifacts, version, commit, True)
        archive_bundle(bundle, output / "maven-payload.tar")


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
    """Dispatch payload construction, validation, or validation-after-safe-extraction."""
    if sys.argv[1:] == ["build"]:
        build_payload(os.environ, pathlib.Path.cwd())
        return
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
    except (
        OSError,
        ValueError,
        subprocess.SubprocessError,
        ET.ParseError,
        tarfile.TarError,
    ) as error:
        raise SystemExit(error) from None
