import hashlib
import io
import tarfile
import zipfile
from pathlib import Path
from typing import Any

import pytest
from temporalio import activity
from temporalio.client import WorkflowFailureError
from temporalio.exceptions import ApplicationError
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from release_automation.build import native, unpack_maven
from release_automation.release import (
    MAVEN_POLICIES,
    PLATFORMS,
    Artifact,
    Candidate,
    Generation,
    Inspection,
    ReleaseInput,
    ReleaseResult,
    ReleaseStatus,
    ReleaseWorkflow,
    Session,
    native_file,
    publication_queue,
    workflow_id,
    workflow_queue,
)


def candidate() -> Candidate:
    """Return the immutable candidate shared by focused tests."""
    return Candidate("v1.2.3", "0123456789abcdef0123456789abcdef01234567")


def artifacts() -> list[Artifact]:
    """Return the fixed native matrix plus the signed Maven payload."""
    value = candidate()
    found = [Artifact(index, f"sha256:{index:064x}", native_file(value, platform)) for index, platform in enumerate(PLATFORMS, 1)]
    found.append(Artifact(99, f"sha256:{99:064x}", "maven-payload.tar"))
    return found


class Publication:
    def __init__(self, ambiguous: bool = False, appears: bool = False, failed: bool = False) -> None:
        """Configure deterministic mocked publication outcomes."""
        self.ambiguous, self.appears, self.failed = ambiguous, appears, failed
        self.publishes = self.inspections = 0

    @activity.defn(name="discoverArtifacts")
    async def discover(self, _value: ReleaseInput) -> list[Artifact]:
        """Freeze the expected test artifact set."""
        return artifacts()

    @activity.defn(name="publishRelease")
    async def publish(self, value: ReleaseInput) -> ReleaseResult:
        """Return success or one typed Maven failure."""
        self.publishes += 1
        if self.failed or self.ambiguous and self.publishes == 1:
            raise ApplicationError(
                "Maven did not complete",
                type="MavenDeploymentFailed" if self.failed else "MavenSubmissionAmbiguous",
                non_retryable=True,
            )
        return ReleaseResult(value.candidate.id, "https://github/release", "https://central/sdk")

    @activity.defn(name="inspectMaven")
    async def inspect(self, value: ReleaseInput) -> Inspection:
        """Model absence, delayed visibility, or a released failed deployment."""
        self.inspections += 1
        visible = self.appears and self.inspections > 1
        return Inspection(
            0,
            [
                Generation(
                    item.number,
                    repositoryState="released" if self.failed else "open" if visible else "absent",
                    portalState="FAILED" if self.failed else "",
                )
                for item in value.generations
            ],
        )

    def all(self):  # type: ignore[no-untyped-def]
        """Return all three mocked Activities for Worker registration."""
        return [self.discover, self.publish, self.inspect]


async def run_release(*, ambiguous: bool = False, appears: bool = False, failed: bool = False) -> tuple[ReleaseResult, ReleaseStatus]:
    """Run the complete Workflow against local Temporal and mocked Activities."""
    value, publication = candidate(), Publication(ambiguous, appears, failed)
    async with await WorkflowEnvironment.start_time_skipping() as env:
        async with (
            Worker(env.client, task_queue=workflow_queue(value.id), workflows=[ReleaseWorkflow]),
            Worker(env.client, task_queue=publication_queue(value.id, 0), activities=publication.all()),
            Worker(env.client, task_queue=publication_queue(value.id, 1), activities=publication.all()),
        ):
            handle = await env.client.start_workflow(
                "ReleaseWorkflow",
                value,
                id=workflow_id(value),
                task_queue=workflow_queue(value.id),
                result_type=ReleaseResult,
            )
            await handle.execute_update("buildsReady")
            result = await handle.result()
            return result, await handle.query("status", result_type=ReleaseStatus)


async def test_release_succeeds_from_one_merge_update() -> None:
    """Publish after the merge-triggered build completion update."""
    result, status = await run_release()
    assert result.github.endswith("/release")
    assert status.phase == "PUBLISHED"
    assert len(status.artifacts) == len(PLATFORMS) + 1


async def test_ambiguous_maven_submission_gets_one_replacement() -> None:
    """Advance from generation zero to one only after repeated absence."""
    _, status = await run_release(ambiguous=True)
    assert [item.number for item in status.generations] == [0, 1]


async def test_delayed_repository_visibility_keeps_generation_zero() -> None:
    """Do not replace a Maven repository that becomes visible after delay."""
    _, status = await run_release(ambiguous=True, appears=True)
    assert [item.number for item in status.generations] == [0]


async def test_failed_replacement_is_bounded() -> None:
    """Stop after a terminal failure also affects generation one."""
    with pytest.raises(WorkflowFailureError):
        await run_release(failed=True)


def test_identity_and_queues_are_release_specific() -> None:
    """Bind Workflow and both privileged queues to one candidate digest."""
    value = candidate()
    assert len(value.id) == 64
    assert workflow_queue(value.id) != publication_queue(value.id, 0)
    assert publication_queue(value.id, 0) != publication_queue(value.id, 1)
    assert len(MAVEN_POLICIES) == 17
    with pytest.raises(ValueError, match="generations"):
        publication_queue(value.id, 2)


def test_native_archives_are_reproducible(tmp_path: Path) -> None:
    """Package identical Linux and Windows bytes deterministically."""
    source = tmp_path / "binary"
    source.write_bytes(b"native bytes")
    first, second, windows = tmp_path / "one.tgz", tmp_path / "two.tgz", tmp_path / "one.zip"
    native(source, first, "root", "server", False)
    native(source, second, "root", "server", False)
    native(source, windows, "root", "server.exe", True)
    assert first.read_bytes() == second.read_bytes()
    with zipfile.ZipFile(windows) as archive:
        assert archive.namelist() == ["root/server.exe"]


def test_maven_unpack_rejects_traversal(tmp_path: Path) -> None:
    """Reject a signed-payload member that escapes its extraction root."""
    archive = tmp_path / "payload.tar"
    with tarfile.open(archive, "w") as bundle:
        member = tarfile.TarInfo("../outside")
        member.size = 1
        bundle.addfile(member, io.BytesIO(b"x"))
    with pytest.raises(ValueError, match="Unexpected Maven archive"):
        unpack_maven(archive, tmp_path / "output")


@pytest.mark.asyncio
async def test_download_requires_frozen_archive_digest(tmp_path: Path) -> None:
    """Reject Actions bytes that differ from the frozen discovery result."""
    content = io.BytesIO()
    with zipfile.ZipFile(content, "w") as archive:
        archive.writestr("asset.zip", b"asset")
    value = ReleaseInput(candidate(), [], [Generation(0)])
    session = Session(value, tmp_path, {"GH_TOKEN": "g", "RH_USER": "u", "RH_PASSWORD": "p"})

    async def request(*_args: Any, **_kwargs: Any) -> tuple[int, bytes]:
        """Return one mocked Actions artifact archive."""
        return 200, content.getvalue()

    session.request = request  # type: ignore[method-assign]
    artifact = Artifact(1, "sha256:" + "0" * 64, "asset.zip")
    with pytest.raises(RuntimeError, match="bytes differ"):
        await session.download(artifact, tmp_path / "download")
    artifact.digest = f"sha256:{hashlib.sha256(content.getvalue()).hexdigest()}"
    assert (await session.download(artifact, tmp_path / "download")).read_bytes() == b"asset"
    session.temp.cleanup()
