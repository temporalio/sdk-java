from __future__ import annotations

import asyncio
import json
import os
import signal
import tempfile
from collections.abc import Mapping
from pathlib import Path
from typing import Any, TypeVar, cast

from temporalio import activity
from temporalio.client import Client
from temporalio.common import WorkflowIDConflictPolicy, WorkflowIDReusePolicy
from temporalio.converter import value_to_type
from temporalio.exceptions import ApplicationError

from .models import (
    ControlEvidence,
    MavenReceipt,
    OwnershipClaim,
    OwnershipStatus,
    PublicationInput,
    ReleaseIdentity,
    ReleaseResult,
    github_maven_artifact_name,
    json_value,
    maven_artifacts,
    ownership_queue,
    ownership_workflow_id,
    publication_queue,
)

T = TypeVar("T")
PASSTHROUGH_ENV = {
    "PATH",
    "Path",
    "SystemRoot",
    "COMSPEC",
    "PATHEXT",
    "HOME",
    "USERPROFILE",
    "TMPDIR",
    "TMP",
    "TEMP",
    "LANG",
    "LC_ALL",
    "JAVA_HOME",
    "GRAALVM_HOME",
    "CI",
    "SystemDrive",
}


class OwnershipActivities:
    def __init__(self, client: Client) -> None:
        self.client = client

    @activity.defn(name="claimTemporal")
    async def claim_temporal(self, release: ReleaseIdentity) -> OwnershipStatus:
        release.validate()
        return await claim_ownership(
            self.client,
            OwnershipClaim(
                release.candidate.tag, release.candidate.commitSha, release.digest(), "TEMPORAL"
            ),
        )

    @activity.defn(name="handoffManual")
    async def handoff_manual(
        self, release: ReleaseIdentity, evidence: ControlEvidence
    ) -> OwnershipStatus:
        release.validate()
        evidence.validate()
        return await claim_ownership(
            self.client,
            OwnershipClaim(
                release.candidate.tag,
                release.candidate.commitSha,
                release.digest(),
                "MANUAL",
                evidence.githubActor,
                evidence.githubRunId,
                True,
            ),
        )


def ownership_handle(client: Client, tag: str):  # type: ignore[no-untyped-def]
    return client.get_workflow_handle(ownership_workflow_id(tag))


async def claim_ownership(client: Client, claim: OwnershipClaim) -> OwnershipStatus:
    claim.validate()
    handle = await client.start_workflow(
        "ReleaseOwnershipWorkflow",
        claim,
        id=ownership_workflow_id(claim.tag),
        task_queue=ownership_queue(claim.tag),
        id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
        id_conflict_policy=WorkflowIDConflictPolicy.USE_EXISTING,
    )
    return cast(
        OwnershipStatus, await handle.execute_update("claim", claim, result_type=OwnershipStatus)
    )


async def ownership_status(client: Client, tag: str) -> OwnershipStatus | None:
    return cast(
        OwnershipStatus | None,
        await ownership_handle(client, tag).query("status", result_type=OwnershipStatus | None),
    )


class CommandError(RuntimeError):
    def __init__(self, status: int, command: str) -> None:
        super().__init__(f"{command} exited with status {status}.")
        self.status = status


async def run_trusted_command(cwd: Path, script: Path, env: Mapping[str, str]) -> list[str]:
    command = [
        r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else "bash",
        _bash_path(script),
    ]
    child_env = {key: value for key, value in os.environ.items() if key in PASSTHROUGH_ENV}
    child_env.update(env)
    options: dict[str, Any] = {}
    if os.name == "nt":
        options["creationflags"] = 0x00000200  # Windows CREATE_NEW_PROCESS_GROUP.
    else:
        options["start_new_session"] = True
    process = await asyncio.create_subprocess_exec(
        *command,
        cwd=cwd,
        env=child_env,
        stdout=asyncio.subprocess.PIPE,
        stderr=None,
        **options,
    )

    async def heartbeat() -> None:
        while process.returncode is None:
            await asyncio.sleep(15)
            activity.heartbeat("External release command is running.")

    beat = asyncio.create_task(heartbeat())
    try:
        stdout, _ = await process.communicate()
    except asyncio.CancelledError:
        await _terminate_tree(process)
        raise
    finally:
        beat.cancel()
        await asyncio.gather(beat, return_exceptions=True)
    if process.returncode:
        raise CommandError(process.returncode, command[0])
    return stdout.decode().splitlines()


async def _terminate_tree(process: asyncio.subprocess.Process) -> None:
    if process.returncode is not None:
        return
    if os.name == "nt":
        killer = await asyncio.create_subprocess_exec(
            "taskkill", "/PID", str(process.pid), "/T", "/F"
        )
        await killer.wait()
    else:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            await asyncio.wait_for(process.wait(), 5)
        except TimeoutError:
            os.killpg(process.pid, signal.SIGKILL)
    await process.wait()


def _bash_path(path: Path) -> str:
    raw = str(path)
    value = raw if len(raw) >= 3 and raw[1:3] in {":/", ":\\"} else str(path.absolute())
    value = value.replace("\\", "/")
    return f"/{value[0].lower()}{value[2:]}" if len(value) >= 3 and value[1:3] == ":/" else value


class PublicationActivities:
    def __init__(
        self,
        trusted_root: Path,
        source_root: Path,
        client: Client,
        environment: Mapping[str, str],
        started: asyncio.Event,
        completed: asyncio.Future[BaseException | None],
    ) -> None:
        self.trusted_root, self.source_root, self.client = trusted_root, source_root, client
        self.environment, self.started, self.completed = dict(environment), started, completed

    @activity.defn(name="preflight")
    async def preflight(self, value: PublicationInput) -> None:
        await self._run(value, "preflight", None)

    @activity.defn(name="reconcileMavenRepository")
    async def reconcile_maven_repository(
        self, value: PublicationInput, allow_creation: bool
    ) -> str:
        return await self._run(value, "maven-repository", str, allow_creation)

    @activity.defn(name="reconcileMavenPortal")
    async def reconcile_maven_portal(self, value: PublicationInput) -> str:
        return await self._run(value, "maven-portal", str)

    @activity.defn(name="publishMaven")
    async def publish_maven(self, value: PublicationInput) -> MavenReceipt:
        return await self._run(value, "maven-publish", MavenReceipt)

    @activity.defn(name="reconcileGithubDraft")
    async def reconcile_github_draft(self, value: PublicationInput) -> str:
        return await self._run(value, "github-draft", str)

    @activity.defn(name="publishGithubRelease")
    async def publish_github_release(
        self, value: PublicationInput, central_url: str
    ) -> ReleaseResult:
        return await self._run(value, "github-publish", ReleaseResult)

    async def _run(
        self,
        value: PublicationInput,
        stage: str,
        result_type: type[T] | None,
        allow_creation: bool = False,
    ) -> T:
        self.started.set()
        try:
            result = await self._command(value, stage, result_type, allow_creation)
        except BaseException as error:
            if not self.completed.done():
                self.completed.set_result(error)
            raise
        if not self.completed.done():
            self.completed.set_result(None)
        return result

    async def _command(
        self, value: PublicationInput, stage: str, result_type: type[T] | None, allow_creation: bool
    ) -> T:
        try:
            expectation = Path(self._required("RELEASE_EXPECTATION_FILE"))
            trusted_commit = self._required("TRUSTED_WORKER_COMMIT")
        except ValueError as error:
            raise ApplicationError(
                str(error), type="InvalidApproval", non_retryable=True
            ) from error
        expected = value_to_type(PublicationInput, json.loads(expectation.read_text()))
        try:
            await validate_publication(
                value,
                expected,
                activity.info(),
                trusted_commit,
                self.client,
            )
        except ApplicationError:
            raise
        except ValueError as error:
            raise ApplicationError(
                str(error), type="InvalidApproval", non_retryable=True
            ) from error
        paths = []
        for suffix in ("-input.json", "-output.json", "-maven.json"):
            descriptor, name = tempfile.mkstemp(prefix="temporal-release-", suffix=suffix)
            os.close(descriptor)
            paths.append(Path(name))
        input_file, output_file, artifacts_file = paths
        try:
            input_file.write_text(json.dumps(json_value(value), separators=(",", ":")))
            artifacts_file.write_text(
                json.dumps(maven_artifacts(value.release.candidate.mavenPolicy))
            )
            env = {
                "RELEASE_INPUT_FILE": str(input_file),
                "RELEASE_OUTPUT_FILE": str(output_file),
                "RELEASE_MAVEN_ARTIFACTS_FILE": str(artifacts_file),
                "RELEASE_STAGE": stage,
                "RELEASE_ALLOW_MAVEN_REPOSITORY_CREATION": str(allow_creation).lower(),
                "TRUSTED_AUTOMATION_ROOT": str(self.trusted_root),
            }
            for name in ("TRUSTED_WORKER_COMMIT", "GH_TOKEN") + (
                ("RH_USER", "RH_PASSWORD") if stage.startswith("maven-") else ()
            ):
                if self.environment.get(name):
                    env[name] = self.environment[name]
            output = await run_trusted_command(
                self.source_root,
                self.trusted_root / ".github/scripts/temporal-release/reconcile-publication.sh",
                env,
            )
            if output:
                raise RuntimeError("Publication command wrote unexpected standard output.")
            if result_type is None:
                return None  # type: ignore[return-value]
            raw: Any = json.loads(output_file.read_text())
            return cast(T, value_to_type(result_type, raw))
        except CommandError as error:
            failures = {
                42: (
                    "An immutable external release identity or checksum conflicts.",
                    "ReleaseIdentityConflict",
                ),
                43: ("GitHub approval evidence is invalid.", "InvalidApproval"),
                44: (
                    "A durable Maven intent has no discoverable Sonatype repository; an authenticated release manager must inspect Sonatype before authorizing another submission generation.",
                    "MavenSubmissionAmbiguous",
                ),
                45: (
                    "The exact Publisher Portal deployment failed validation.",
                    "MavenDeploymentFailed",
                ),
                46: (
                    "An exact GitHub Actions artifact expired or was deleted.",
                    "ArtifactUnavailable",
                ),
            }
            if error.status in failures:
                message, kind = failures[error.status]
                raise ApplicationError(message, type=kind, non_retryable=True) from error
            raise
        finally:
            for path in paths:
                path.unlink(missing_ok=True)

    def _required(self, name: str) -> str:
        value = self.environment.get(name)
        if not value:
            raise ValueError(f"Required Worker value is missing: {name}")
        return value


async def validate_publication(
    value: PublicationInput,
    expected: PublicationInput,
    info: activity.Info,
    trusted_commit: str,
    client: Client,
) -> None:
    for item in (value, expected):
        validate_publication_input(item)
    checks = (
        (json_value(expected), json_value(value), "privileged publication input"),
        (value.workflowId, info.workflow_id, "Activity Workflow ID"),
        (value.runId, info.workflow_run_id, "Activity run ID"),
        (value.workflowId, value.approval.workflowId, "approval Workflow ID"),
        (value.runId, value.approval.runId, "approval run ID"),
        (value.release.digest(), value.approval.releaseDigest, "release digest"),
        (
            value.release.candidate.trustedAutomationCommit,
            value.approval.trustedWorkerCommit,
            "frozen trusted Worker commit",
        ),
        (value.approval.trustedWorkerCommit, trusted_commit, "trusted Worker commit"),
        (
            publication_queue(value.release, value.mavenSubmissionGeneration),
            info.task_queue,
            "publication Task Queue",
        ),
    )
    if (
        next((name for expected_value, actual, name in checks if expected_value != actual), None)
        is not None
    ):
        raise ApplicationError(
            "Publication input does not match the privileged Actions run.",
            type="InvalidApproval",
            non_retryable=True,
        )
    ownership = await ownership_status(client, value.release.candidate.tag)
    if (
        ownership is None
        or ownership.owner != "TEMPORAL"
        or ownership.commitSha != value.release.candidate.commitSha
        or ownership.releaseDigest != value.release.digest()
    ):
        raise ApplicationError(
            "Temporal does not own this exact tag, commit, and release identity.",
            type="InvalidApproval",
            non_retryable=True,
        )


def validate_publication_input(value: PublicationInput) -> None:
    value.release.validate()
    value.approvalRequest.validate()
    value.approval.validate()
    if not value.approvalRequest.matches(value.approval):
        raise ValueError("Approval does not match its durable request.")
    if value.mavenSubmissionGeneration < 0:
        raise ValueError("Maven submission generation is invalid.")
    if value.mavenSubmissionGeneration == 0 and value.mavenRetryAuthorization is not None:
        raise ValueError("Initial Maven submission has retry authorization.")
    if value.mavenSubmissionGeneration > 0:
        if value.mavenRetryAuthorization is None:
            raise ValueError("A Maven retry has no external authorization binding.")
        value.mavenRetryAuthorization.validate()
        if (
            value.mavenRetryAuthorization.mavenSubmissionGeneration
            != value.mavenSubmissionGeneration
        ):
            raise ValueError("Maven retry generation does not match.")
    payload = value.mavenPayload
    if payload is None:
        raise ValueError("The frozen Maven payload is missing.")
    payload.validate()
    if (
        github_maven_artifact_name(value.release) != payload.artifactName
        or len(payload.files) != 1
        or payload.files[0].name != "maven-payload.tar"
    ):
        raise ValueError("The frozen Maven payload identity is invalid.")
    found: set[int] = set()
    for generation in value.mavenGenerations:
        generation.validate(value.release.digest())
        if generation.generation in found:
            raise ValueError("Maven generation state is duplicated.")
        found.add(generation.generation)
