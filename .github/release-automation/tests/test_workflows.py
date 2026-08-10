import asyncio

import pytest
from temporalio import activity
from temporalio.client import WorkflowFailureError, WorkflowHandle
from temporalio.exceptions import ApplicationError
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from release_automation.activities import validate_publication_input
from release_automation.models import (
    NATIVE_PLATFORMS,
    CandidateIdentity,
    GithubArtifactReceipt,
    MavenGeneration,
    MavenInspection,
    PublicationInput,
    ReleaseIdentity,
    ReleaseResult,
    ReleaseStatus,
    candidate_queue,
    candidate_workflow_id,
    github_maven_artifact_name,
    github_native_artifact_name,
    native_artifact_name,
    publication_queue,
)
from release_automation.workflows import ReleaseWorkflow


def fixture_release() -> ReleaseIdentity:
    candidate = CandidateIdentity("v1.2.3", "0" * 40, "b" * 40, "current", 123)
    receipts = [
        GithubArtifactReceipt(
            100 + i,
            200 + i,
            github_native_artifact_name(candidate, platform),
            f"sha256:{100 + i:064x}",
            native_artifact_name(candidate.version, platform),
        )
        for i, platform in enumerate(NATIVE_PLATFORMS, 1)
    ]
    return ReleaseIdentity(candidate, receipts)


def maven_payload(release: ReleaseIdentity) -> GithubArtifactReceipt:
    return GithubArtifactReceipt(
        999,
        888,
        github_maven_artifact_name(release),
        f"sha256:{99:064x}",
        "maven-payload.tar",
    )


class Publication:
    def __init__(
        self, ambiguous_first: bool = False, appears: bool = False, failure: str = ""
    ) -> None:
        self.ambiguous_first = ambiguous_first
        self.appears = appears
        self.failure = failure
        self.publishes = self.inspections = 0

    @activity.defn(name="publishRelease")
    async def publish(self, value: PublicationInput) -> ReleaseResult:
        self.publishes += 1
        if self.failure or self.ambiguous_first and self.publishes == 1:
            raise ApplicationError(
                "Maven publication did not complete",
                type=self.failure or "MavenSubmissionAmbiguous",
                non_retryable=True,
            )
        return ReleaseResult(
            value.release.digest(),
            "https://github.example/release",
            "https://central.example/artifact",
        )

    @activity.defn(name="inspectMaven")
    async def inspect(self, value: PublicationInput) -> MavenInspection:
        self.inspections += 1
        visible = self.appears and self.inspections > 1
        failed = self.failure == "MavenDeploymentFailed"
        return MavenInspection(
            0,
            [
                MavenGeneration(
                    generation.generation,
                    True,
                    None,
                    "released" if failed else "open" if visible else "absent",
                    None,
                    "FAILED" if failed else "",
                )
                for generation in value.mavenGenerations
            ],
        )

    def all(self):  # type: ignore[no-untyped-def]
        return [self.publish, self.inspect]


async def wait_phase(handle: WorkflowHandle, phase: str) -> ReleaseStatus:
    for _ in range(40):
        status = await handle.query("status", result_type=ReleaseStatus)
        if status.phase == phase:
            return status
        await asyncio.sleep(0.05)
    raise AssertionError(f"release did not reach {phase}")


async def run_release(
    ambiguous_first: bool = False, appears: bool = False, failure: str = ""
) -> tuple[ReleaseResult, ReleaseStatus]:
    release = fixture_release()
    candidate = release.candidate
    async with await WorkflowEnvironment.start_time_skipping() as env:
        publication = Publication(ambiguous_first, appears, failure)
        async with (
            Worker(env.client, task_queue=candidate_queue(candidate), workflows=[ReleaseWorkflow]),
            Worker(env.client, task_queue=publication_queue(release), activities=publication.all()),
            Worker(
                env.client, task_queue=publication_queue(release, 1), activities=publication.all()
            ),
        ):
            handle = await env.client.start_workflow(
                "ReleaseWorkflow",
                candidate,
                id=candidate_workflow_id(candidate),
                task_queue=candidate_queue(candidate),
                result_type=ReleaseResult,
            )
            for platform, receipt in zip(NATIVE_PLATFORMS, release.artifacts, strict=True):
                await handle.execute_update("recordArtifact", args=[platform, receipt])
            await wait_phase(handle, "AWAITING_MAVEN_PAYLOAD")
            await handle.execute_update("recordMavenPayload", maven_payload(release))
            return await handle.result(), await handle.query("status", result_type=ReleaseStatus)


async def test_merged_candidate_publishes_without_secondary_approval() -> None:
    result, status = await run_release()
    assert result.releaseDigest == fixture_release().digest()
    assert status.phase == "PUBLISHED"


async def test_safe_maven_ambiguity_advances_generation() -> None:
    _, status = await run_release(ambiguous_first=True)
    assert status.phase == "PUBLISHED"
    assert status.mavenGenerations[-1].generation == 1
    assert [item.generation for item in status.mavenGenerations] == [0, 1]


async def test_delayed_repository_visibility_does_not_advance_generation() -> None:
    _, status = await run_release(ambiguous_first=True, appears=True)
    assert [item.generation for item in status.mavenGenerations] == [0]


async def test_failed_portal_retries_only_once() -> None:
    with pytest.raises(WorkflowFailureError):
        await run_release(failure="MavenDeploymentFailed")


def test_publication_input_carries_exact_release_digest() -> None:
    release = fixture_release()
    value = PublicationInput(
        release,
        maven_payload(release),
        [MavenGeneration(0)],
    )
    validate_publication_input(value)
    value.mavenGenerations = []
    with pytest.raises(ValueError, match="generations"):
        validate_publication_input(value)


async def test_native_receipts_freeze_identity_in_same_workflow() -> None:
    expected = fixture_release()
    async with await WorkflowEnvironment.start_time_skipping() as env:
        async with Worker(
            env.client,
            task_queue=candidate_queue(expected.candidate),
            workflows=[ReleaseWorkflow],
        ):
            handle = await env.client.start_workflow(
                "ReleaseWorkflow",
                expected.candidate,
                id=candidate_workflow_id(expected.candidate),
                task_queue=candidate_queue(expected.candidate),
                result_type=ReleaseResult,
            )
            for platform, receipt in zip(NATIVE_PLATFORMS, expected.artifacts, strict=True):
                status = await handle.execute_update(
                    "recordArtifact", args=[platform, receipt], result_type=ReleaseStatus
                )
            assert status.identity is not None
            status = await wait_phase(handle, "AWAITING_MAVEN_PAYLOAD")
            assert status.identity is not None
            assert status.identity.digest() == expected.digest()
