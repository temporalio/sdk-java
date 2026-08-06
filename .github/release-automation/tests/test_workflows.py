from __future__ import annotations

import pytest
from temporalio import activity
from temporalio.client import WorkflowHandle
from temporalio.exceptions import ApplicationError
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from release_automation.activities import OwnershipActivities
from release_automation.models import (
    NATIVE_PLATFORMS,
    ApprovalEvidence,
    ApprovalRequest,
    ArtifactEntry,
    ArtifactManifest,
    CandidateIdentity,
    ControlEvidence,
    GithubArtifactReceipt,
    MavenGenerationState,
    MavenReceipt,
    PublicationInput,
    ReleaseIdentity,
    ReleaseResult,
    ReleaseStatus,
    candidate_queue,
    candidate_workflow_id,
    github_maven_artifact_name,
    github_native_artifact_name,
    native_artifact_name,
    ownership_queue,
    publication_queue,
    release_queue,
    release_workflow_id,
)
from release_automation.workflows import (
    CandidateWorkflow,
    ReleaseOwnershipWorkflow,
    ReleaseWorkflow,
)


def fixture_release() -> ReleaseIdentity:
    candidate = CandidateIdentity("v1.2.3", "0" * 40, "a" * 64, "b" * 40, "current")
    receipts = [
        GithubArtifactReceipt(
            100 + i,
            200 + i,
            github_native_artifact_name(candidate, platform),
            f"sha256:{100 + i:064x}",
            "2026-01-01T00:00:00Z",
            "2026-04-01T00:00:00Z",
            [
                ArtifactEntry(
                    native_artifact_name(candidate.version, platform), f"{i:064x}", 1000 + i
                )
            ],
        )
        for i, platform in enumerate(NATIVE_PLATFORMS, 1)
    ]
    return ReleaseIdentity.create(
        candidate, ArtifactManifest(receipts), "11111111-2222-3333-4444-555555555555"
    )


def maven_payload(release: ReleaseIdentity) -> GithubArtifactReceipt:
    return GithubArtifactReceipt(
        999,
        888,
        github_maven_artifact_name(release),
        f"sha256:{99:064x}",
        "2026-01-01T00:00:00Z",
        "2026-04-01T00:00:00Z",
        [ArtifactEntry("maven-payload.tar", "c" * 64, 9000)],
    )


class SuccessfulPublication:
    def __init__(self, ambiguous_repository: bool = False) -> None:
        self.ambiguous_repository = ambiguous_repository

    @activity.defn(name="preflight")
    async def preflight(self, _input: PublicationInput) -> None:
        pass

    @activity.defn(name="reconcileMavenRepository")
    async def repository(self, _input: PublicationInput, _create: bool) -> str:
        if self.ambiguous_repository:
            raise ApplicationError(
                "repository creation was ambiguous",
                type="MavenSubmissionAmbiguous",
                non_retryable=True,
            )
        return "io-temporal-1000"

    @activity.defn(name="reconcileMavenPortal")
    async def portal(self, _input: PublicationInput) -> str:
        return "12345678-1234-1234-1234-123456789abc"

    @activity.defn(name="publishMaven")
    async def maven(self, _input: PublicationInput) -> MavenReceipt:
        return MavenReceipt(
            "https://central.example/artifact",
            "io-temporal-1000",
            "12345678-1234-1234-1234-123456789abc",
        )

    @activity.defn(name="reconcileGithubDraft")
    async def draft(self, _input: PublicationInput) -> str:
        return "https://github.example/draft"

    @activity.defn(name="publishGithubRelease")
    async def github(self, value: PublicationInput, central: str) -> ReleaseResult:
        return ReleaseResult(value.release.digest(), "https://github.example/release", central)

    def all(self):  # type: ignore[no-untyped-def]
        return [self.preflight, self.repository, self.portal, self.maven, self.draft, self.github]


async def wait_phase(handle: WorkflowHandle, phase: str) -> ReleaseStatus:
    for _ in range(40):
        status = await handle.query("status", result_type=ReleaseStatus)
        if status.phase == phase:
            return status
        await __import__("asyncio").sleep(0.05)
    raise AssertionError(f"release did not reach {phase}")


def evidence(release: ReleaseIdentity, run_id: str):  # type: ignore[no-untyped-def]
    workflow_id = release_workflow_id(release)
    request = ApprovalRequest(
        release.digest(),
        workflow_id,
        run_id,
        300,
        43,
        "ISSUE_node_43",
        "c" * 64,
        "approval-bot",
        release.candidate.trustedAutomationCommit,
    )
    approval = ApprovalEvidence(
        release.digest(),
        workflow_id,
        run_id,
        300,
        "release-manager",
        43,
        "ISSUE_node_43",
        "c" * 64,
        release.candidate.trustedAutomationCommit,
    )
    return request, approval


@pytest.mark.asyncio
async def test_publishes_after_exact_approval_and_payload() -> None:
    release = fixture_release()
    async with await WorkflowEnvironment.start_time_skipping() as env:
        ownership = OwnershipActivities(env.client)
        publication = SuccessfulPublication()
        async with (
            Worker(env.client, task_queue=release_queue(release), workflows=[ReleaseWorkflow]),
            Worker(
                env.client,
                task_queue=ownership_queue(release.candidate.tag),
                workflows=[ReleaseOwnershipWorkflow],
                activities=[ownership.claim_temporal, ownership.handoff_manual],
            ),
            Worker(env.client, task_queue=publication_queue(release), activities=publication.all()),
        ):
            handle = await env.client.start_workflow(
                "ReleaseWorkflow",
                release,
                id=release_workflow_id(release),
                task_queue=release_queue(release),
                result_type=ReleaseResult,
            )
            await wait_phase(handle, "AWAITING_APPROVAL")
            request, approval = evidence(release, handle.result_run_id)
            await handle.execute_update("requestApproval", request)
            await handle.execute_update("approve", approval)
            await handle.execute_update("recordMavenPayload", maven_payload(release))
            result = await handle.result()
            assert result.releaseDigest == release.digest()
            assert (await handle.query("status", result_type=ReleaseStatus)).phase == "PUBLISHED"


@pytest.mark.asyncio
async def test_handoff_transfers_durable_ownership() -> None:
    release = fixture_release()
    async with await WorkflowEnvironment.start_time_skipping() as env:
        ownership = OwnershipActivities(env.client)
        async with (
            Worker(env.client, task_queue=release_queue(release), workflows=[ReleaseWorkflow]),
            Worker(
                env.client,
                task_queue=ownership_queue(release.candidate.tag),
                workflows=[ReleaseOwnershipWorkflow],
                activities=[ownership.claim_temporal, ownership.handoff_manual],
            ),
        ):
            handle = await env.client.start_workflow(
                "ReleaseWorkflow",
                release,
                id=release_workflow_id(release),
                task_queue=release_queue(release),
            )
            await wait_phase(handle, "AWAITING_APPROVAL")
            control = ControlEvidence(
                "handoff-manual",
                release.digest(),
                release_workflow_id(release),
                handle.result_run_id,
                202,
                "release-manager",
                release.candidate.tag,
                release.candidate.commitSha,
                "Test control evidence.",
            )
            status = await handle.execute_update("control", control, result_type=ReleaseStatus)
            assert status.phase == "HANDED_OFF" and status.ownership.owner == "MANUAL"


@pytest.mark.asyncio
async def test_candidate_receipts_start_the_exact_release_child() -> None:
    expected = fixture_release()
    candidate = expected.candidate
    async with await WorkflowEnvironment.start_time_skipping() as env:
        ownership = OwnershipActivities(env.client)
        async with (
            Worker(
                env.client, task_queue=candidate_queue(candidate), workflows=[CandidateWorkflow]
            ),
            Worker(env.client, task_queue=release_queue(expected), workflows=[ReleaseWorkflow]),
            Worker(
                env.client,
                task_queue=ownership_queue(candidate.tag),
                workflows=[ReleaseOwnershipWorkflow],
                activities=[ownership.claim_temporal, ownership.handoff_manual],
            ),
        ):
            handle = await env.client.start_workflow(
                "CandidateWorkflow",
                candidate,
                id=candidate_workflow_id(candidate),
                task_queue=candidate_queue(candidate),
                result_type=ReleaseIdentity,
            )
            for platform, receipt in zip(
                NATIVE_PLATFORMS, expected.manifest.artifacts, strict=True
            ):
                await handle.execute_update("recordArtifact", args=[platform, receipt])
            release = await handle.result()
            assert release.digest() == expected.digest()
            assert release.candidateRunId == handle.result_run_id
            child = env.client.get_workflow_handle(release_workflow_id(release))
            assert (await wait_phase(child, "AWAITING_APPROVAL")).phase == "AWAITING_APPROVAL"


@pytest.mark.asyncio
async def test_pause_and_resume_are_bound_to_the_exact_release_run() -> None:
    release = fixture_release()
    async with await WorkflowEnvironment.start_time_skipping() as env:
        ownership = OwnershipActivities(env.client)
        async with (
            Worker(env.client, task_queue=release_queue(release), workflows=[ReleaseWorkflow]),
            Worker(
                env.client,
                task_queue=ownership_queue(release.candidate.tag),
                workflows=[ReleaseOwnershipWorkflow],
                activities=[ownership.claim_temporal, ownership.handoff_manual],
            ),
        ):
            handle = await env.client.start_workflow(
                "ReleaseWorkflow",
                release,
                id=release_workflow_id(release),
                task_queue=release_queue(release),
            )
            await wait_phase(handle, "AWAITING_APPROVAL")
            common = dict(
                releaseDigest=release.digest(),
                workflowId=release_workflow_id(release),
                runId=handle.result_run_id,
                githubRunId=203,
                githubActor="release-manager",
                tag=release.candidate.tag,
                commitSha=release.candidate.commitSha,
            )
            paused = await handle.execute_update(
                "control",
                ControlEvidence(action="pause", reason="Test pause.", **common),
                result_type=ReleaseStatus,
            )
            assert paused.phase == "PAUSED" and paused.pausedFrom == "AWAITING_APPROVAL"
            common["githubRunId"] = 204
            resumed = await handle.execute_update(
                "control",
                ControlEvidence(action="resume", reason="Test resume.", **common),
                result_type=ReleaseStatus,
            )
            assert resumed.phase == "AWAITING_APPROVAL" and resumed.pausedFrom is None


@pytest.mark.asyncio
async def test_ambiguous_maven_submission_blocks_for_bound_generation_authorization() -> None:
    release = fixture_release()
    async with await WorkflowEnvironment.start_time_skipping() as env:
        ownership = OwnershipActivities(env.client)
        publication = SuccessfulPublication(ambiguous_repository=True)
        async with (
            Worker(env.client, task_queue=release_queue(release), workflows=[ReleaseWorkflow]),
            Worker(
                env.client,
                task_queue=ownership_queue(release.candidate.tag),
                workflows=[ReleaseOwnershipWorkflow],
                activities=[ownership.claim_temporal, ownership.handoff_manual],
            ),
            Worker(
                env.client,
                task_queue=publication_queue(release),
                activities=publication.all(),
            ),
        ):
            handle = await env.client.start_workflow(
                "ReleaseWorkflow",
                release,
                id=release_workflow_id(release),
                task_queue=release_queue(release),
            )
            await wait_phase(handle, "AWAITING_APPROVAL")
            request, approval = evidence(release, handle.result_run_id)
            await handle.execute_update("requestApproval", request)
            await handle.execute_update("approve", approval)
            await handle.execute_update("recordMavenPayload", maven_payload(release))
            blocked = await wait_phase(handle, "BLOCKED")
            assert blocked.pausedFrom == "MAVEN_REPOSITORY"
            assert "MavenSubmissionAmbiguous" in (blocked.lastError or "")
            assert blocked.mavenGenerations[0].submissionStarted


def test_partial_maven_cannot_handoff() -> None:
    release = fixture_release()
    generation = MavenGenerationState.create(release.digest(), 0)
    generation.submissionStarted = True
    with pytest.raises(RuntimeError):
        ReleaseWorkflow.validate_manual_handoff([generation], None, False)
    with pytest.raises(RuntimeError):
        ReleaseWorkflow.validate_manual_handoff([generation], None, True)
    ReleaseWorkflow.validate_manual_handoff([generation], "https://central.example/artifact", False)
