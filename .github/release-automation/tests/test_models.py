import io
import tarfile
from pathlib import Path

import pytest

from release_automation.cli import verify_candidate_origin
from release_automation.maven_payload import extract
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
