import io
import tarfile
from pathlib import Path

import pytest

import release_automation.maven_payload as maven_payload
from release_automation.cli import publication_worker_queues, verify_candidate_origin
from release_automation.maven_payload import archive_bundle, extract, validate, validate_plugin
from release_automation.models import (
    MAVEN_ARTIFACTS,
    MAVEN_POLICIES,
    NATIVE_PLATFORMS,
    CandidateIdentity,
    GithubArtifactReceipt,
    ReleaseIdentity,
    candidate_queue,
    github_native_artifact_name,
    maven_policy_for_projects,
    native_artifact_name,
    publication_queue,
)


def candidate() -> CandidateIdentity:
    """Build a valid immutable candidate used by model tests."""
    return CandidateIdentity(
        "v1.2.3",
        "0123456789abcdef0123456789abcdef01234567",
        "current",
        123,
    )


def artifact(candidate: CandidateIdentity, platform: str, index: int) -> GithubArtifactReceipt:
    """Build one valid native receipt with deterministic test-only identities."""
    return GithubArtifactReceipt(
        1000 + index,
        2000 + index,
        github_native_artifact_name(candidate, platform),
        f"sha256:{index + 100:064x}",
        native_artifact_name(candidate.version, platform),
    )


def release() -> ReleaseIdentity:
    """Build a complete release identity for the fixed native matrix."""
    value = candidate()
    return ReleaseIdentity(
        value,
        [artifact(value, platform, index) for index, platform in enumerate(NATIVE_PLATFORMS, 1)],
    )


def test_identity_platforms_order_and_queues_are_stable() -> None:
    """Release digests ignore receipt order while queues remain purpose-specific."""
    value = release()
    assert len(value.digest()) == 64
    assert publication_queue(value).endswith("-publication-g0")
    assert publication_queue(value, 1) != publication_queue(value)
    assert candidate_queue(value.candidate) not in {
        publication_queue(value),
        publication_queue(value, 1),
    }
    assert "temporal-test-server_1.2.3_macOS_amd64.tar.gz" in {x.fileName for x in value.artifacts}
    reversed_release = ReleaseIdentity(value.candidate, list(reversed(value.artifacts)))
    assert reversed_release.digest() == value.digest()


def test_publication_worker_polls_only_the_bounded_generation_window() -> None:
    """Keep generation-specific queues while covering the one allowed replacement."""
    generation_zero = publication_queue(release())
    generation_one = publication_queue(release(), 1)
    assert publication_worker_queues(generation_zero) == (generation_zero, generation_one)
    assert publication_worker_queues(generation_one) == (generation_one,)
    with pytest.raises(ValueError, match="non-publication"):
        publication_worker_queues("sdk-java-release-invalid-publication-g9")


def test_fixed_platform_and_maven_policy_cannot_drift() -> None:
    """Reject incomplete native matrices and unreviewed Gradle project sets."""
    value = release()
    value.artifacts.pop()
    with pytest.raises(ValueError, match="fixed sdk-java platform set"):
        value.validate()
    assert len(MAVEN_ARTIFACTS) == 17
    for policy, artifacts in MAVEN_POLICIES.items():
        assert maven_policy_for_projects(list(artifacts)) == policy
    with pytest.raises(ValueError, match="reviewed sdk-java Maven policy"):
        maven_policy_for_projects(["temporal-sdk"])


def test_candidate_is_bound_to_authorized_push_run() -> None:
    """Accept only the exact trusted candidate workflow and branch origin."""
    value = candidate()
    run = {
        "id": 123,
        "event": "push",
        "path": ".github/workflows/temporal-release-candidate.yml",
        "head_sha": value.commitSha,
        "head_branch": "main",
        "head_repository": {"full_name": "temporalio/sdk-java"},
    }
    verify_candidate_origin(value, run)
    run["head_branch"] = "untrusted"
    with pytest.raises(RuntimeError, match="does not authorize"):
        verify_candidate_origin(value, run)


def test_maven_payload_rejects_archive_traversal(tmp_path: Path) -> None:
    """Reject a tar member that attempts to escape the extraction root."""
    archive = tmp_path / "payload.tar"
    with tarfile.open(archive, "w") as bundle:
        member = tarfile.TarInfo("../outside")
        member.size = 1
        bundle.addfile(member, io.BytesIO(b"x"))
    with pytest.raises(ValueError, match="unexpected archive path"):
        extract(archive, tmp_path / "output")


def test_maven_build_helpers_pin_plugin_and_archive_deterministically(tmp_path: Path) -> None:
    """Keep source policy fixed and create repeatable receipt-backed Maven archives."""
    build = tmp_path / "build.gradle"
    build.write_text("plugins { id 'io.github.gradle-nexus.publish-plugin' version '1.3.0' }")
    validate_plugin(build)
    build.write_text("plugins { id 'io.github.gradle-nexus.publish-plugin' version '2.0.0' }")
    with pytest.raises(ValueError, match="supported Gradle Nexus plugin"):
        validate_plugin(build)

    bundle = tmp_path / "bundle"
    payload = bundle / "repository/io/temporal/example/1.0.0/example-1.0.0.pom"
    payload.parent.mkdir(parents=True)
    payload.write_text("exact")
    (bundle / "manifest.tsv").write_text("manifest")
    first, second = tmp_path / "first.tar", tmp_path / "second.tar"
    archive_bundle(bundle, first)
    archive_bundle(bundle, second)
    assert first.read_bytes() == second.read_bytes()
    extract(first, tmp_path / "extracted")
    assert (
        tmp_path / "extracted/repository/io/temporal/example/1.0.0/example-1.0.0.pom"
    ).read_text() == "exact"


def test_maven_payload_builds_without_forwarding_secrets(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Exercise the Python build pipeline while keeping secrets outside tool environments."""
    source, trusted, output = tmp_path / "source", tmp_path / "trusted", tmp_path / "output"
    source.mkdir()
    trusted.mkdir()
    commit = "0123456789abcdef0123456789abcdef01234567"
    env = {
        "JAR_SIGNING_KEY": "a2V5",
        "JAR_SIGNING_KEY_ID": "test",
        "JAR_SIGNING_KEY_PASSWORD": "secret",
        "MAVEN_ARTIFACTS_JSON": '["temporal-bom"]',
        "MAVEN_PAYLOAD_COMMIT": commit,
        "MAVEN_PAYLOAD_OUTPUT": str(output),
        "MAVEN_PAYLOAD_VERSION": "1.2.3",
        "TRUSTED_AUTOMATION_ROOT": str(trusted),
    }

    def build(
        _source: Path, _trusted: Path, _sandbox: Path, generated: Path, version: str, sha: str
    ) -> None:
        """Stand in for Docker by generating the unsigned BOM files."""
        root = generated / "io/temporal/temporal-bom" / version
        root.mkdir(parents=True)
        (root / f"temporal-bom-{version}.module").write_text("{}")
        (root / f"temporal-bom-{version}.pom").write_text(
            f"<project><groupId>io.temporal</groupId><artifactId>temporal-bom</artifactId>"
            f"<version>{version}</version><scm><tag>{sha}</tag></scm></project>"
        )

    def sign(root: Path, _home: Path, _env: object) -> None:
        """Stand in for GPG while creating the exact required sidecar set."""
        for path in list(root.rglob("*.pom")) + list(root.rglob("*.module")):
            for suffix in (".asc", ".md5", ".sha1"):
                Path(f"{path}{suffix}").write_text(suffix)

    monkeypatch.setattr(maven_payload, "git_head", lambda _root: commit)
    monkeypatch.setattr(maven_payload, "build_unsigned", build)
    monkeypatch.setattr(maven_payload, "sign", sign)
    monkeypatch.setenv("JAR_SIGNING_KEY_PASSWORD", "must-not-reach-tools")
    assert "JAR_SIGNING_KEY_PASSWORD" not in maven_payload.tool_environment()
    maven_payload.build_payload(env, source)
    extracted = tmp_path / "built"
    extract(output / "maven-payload.tar", extracted)
    validate(
        extracted / "repository",
        extracted / "manifest.tsv",
        ["temporal-bom"],
        "1.2.3",
        commit,
        True,
    )
