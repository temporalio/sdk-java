from __future__ import annotations

from copy import deepcopy

import pytest

from release_automation.models import (
    MAVEN_ARTIFACTS,
    MAVEN_POLICIES,
    NATIVE_PLATFORMS,
    ApprovalEvidence,
    ApprovalRequest,
    ArtifactEntry,
    ArtifactManifest,
    CandidateIdentity,
    GithubArtifactReceipt,
    ReleaseIdentity,
    candidate_queue,
    github_native_artifact_name,
    maven_policy_for_projects,
    native_artifact_name,
    publication_queue,
    release_queue,
)


def candidate() -> CandidateIdentity:
    return CandidateIdentity(
        "v1.2.3",
        "0123456789abcdef0123456789abcdef01234567",
        "a" * 64,
        "abcdefabcdefabcdefabcdefabcdefabcdefabcd",
        "current",
    )


def artifact(candidate: CandidateIdentity, platform: str, index: int) -> GithubArtifactReceipt:
    return GithubArtifactReceipt(
        1000 + index,
        2000 + index,
        github_native_artifact_name(candidate, platform),
        f"sha256:{index + 100:064x}",
        "2026-01-01T00:00:00Z",
        "2026-04-01T00:00:00Z",
        [
            ArtifactEntry(
                native_artifact_name(candidate.version, platform), f"{index:064x}", 1000 + index
            )
        ],
    )


def release() -> ReleaseIdentity:
    value = candidate()
    return ReleaseIdentity.create(
        value,
        ArtifactManifest(
            [artifact(value, platform, index) for index, platform in enumerate(NATIVE_PLATFORMS, 1)]
        ),
        "11111111-2222-3333-4444-555555555555",
    )


def test_identity_platforms_order_and_queues_are_stable() -> None:
    value = release()
    assert len(value.digest()) == 64
    assert publication_queue(value).endswith("-publication-g0")
    assert publication_queue(value, 1) != publication_queue(value)
    assert candidate_queue(value.candidate) != release_queue(value)
    assert "temporal-test-server_1.2.3_macOS_amd64.tar.gz" in {
        x.files[0].name for x in value.manifest.artifacts
    }
    reversed_release = ReleaseIdentity.create(
        value.candidate, ArtifactManifest(list(reversed(value.manifest.artifacts))), ""
    )
    assert reversed_release.digest() == value.digest()


def test_fixed_platform_and_maven_policy_cannot_drift() -> None:
    value = release()
    value.manifest.artifacts.pop()
    value.manifestSha256 = value.manifest.digest()
    with pytest.raises(ValueError, match="fixed sdk-java platform set"):
        value.validate()
    assert len(MAVEN_ARTIFACTS) == 17
    for policy, artifacts in MAVEN_POLICIES.items():
        assert maven_policy_for_projects(list(artifacts)) == policy
    with pytest.raises(ValueError, match="reviewed sdk-java Maven policy"):
        maven_policy_for_projects(["temporal-sdk"])


def test_approval_is_bound_to_exact_issue_and_run() -> None:
    value = release()
    workflow_id = f"sdk-java-release/{value.digest()}"
    run_id = "11111111-2222-3333-4444-555555555555"
    request = ApprovalRequest(
        value.digest(),
        workflow_id,
        run_id,
        100,
        42,
        "ISSUE_node_42",
        "b" * 64,
        "approval-bot",
        value.candidate.trustedAutomationCommit,
    )
    exact = ApprovalEvidence(
        value.digest(),
        workflow_id,
        run_id,
        101,
        "release-manager",
        42,
        "ISSUE_node_42",
        "b" * 64,
        value.candidate.trustedAutomationCommit,
    )
    replay = deepcopy(exact)
    replay.githubIssueNumber = 43
    assert request.matches(exact)
    assert not request.matches(replay)
    retried = deepcopy(request)
    retried.githubRunId += 1
    assert request.same_issue(retried)
