import asyncio
import json
import os
import signal
import subprocess
import tempfile
from collections.abc import Mapping
from pathlib import Path
from typing import Any, TypeVar, cast

from temporalio import activity
from temporalio.converter import value_to_type
from temporalio.exceptions import ApplicationError

from .models import (
    MavenInspection,
    PublicationInput,
    ReleaseResult,
    candidate_workflow_id,
    json_value,
    matches_maven_payload,
    maven_artifacts,
    publication_queue,
)

T = TypeVar("T")
PASSTHROUGH_ENV = {"PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "JAVA_HOME", "CI"}
COMMAND_FAILURES = {
    42: ("Immutable external identity or checksum conflict.", "ReleaseIdentityConflict"),
    44: ("Maven repository identity is not yet visible.", "MavenSubmissionAmbiguous"),
    45: ("Portal deployment failed validation.", "MavenDeploymentFailed"),
    46: ("GitHub artifact expired or was deleted.", "ArtifactUnavailable"),
}


async def run_trusted_command(cwd: Path, script: Path, env: Mapping[str, str]) -> list[str]:
    command = ["bash", str(script.absolute())]
    child_env = {key: value for key, value in os.environ.items() if key in PASSTHROUGH_ENV}
    child_env.update(env)
    process = await asyncio.create_subprocess_exec(
        *command,
        cwd=cwd,
        env=child_env,
        stdout=asyncio.subprocess.PIPE,
        start_new_session=True,
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
        raise subprocess.CalledProcessError(process.returncode, command)
    return stdout.decode().splitlines()


async def _terminate_tree(process: asyncio.subprocess.Process) -> None:
    if process.returncode is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        await asyncio.wait_for(process.wait(), 5)
    except TimeoutError:
        os.killpg(process.pid, signal.SIGKILL)
    await process.wait()


class PublicationActivities:
    def __init__(
        self,
        trusted_root: Path,
        source_root: Path,
        environment: Mapping[str, str],
    ) -> None:
        self.trusted_root, self.source_root = trusted_root, source_root
        self.environment = dict(environment)

    @activity.defn(name="publishRelease")
    async def publish_release(self, value: PublicationInput) -> ReleaseResult:
        return await self._command(value, "all", ReleaseResult)

    @activity.defn(name="inspectMaven")
    async def inspect_maven(self, value: PublicationInput) -> MavenInspection:
        return await self._command(value, "inspect", MavenInspection)

    async def _command(self, value: PublicationInput, stage: str, result_type: type[T]) -> T:
        try:
            trusted_commit = self.environment.get("TRUSTED_WORKER_COMMIT")
            expected_run = self.environment.get("EXPECTED_RUN_ID")
            if not trusted_commit or not expected_run:
                raise ValueError("Required publication Worker identity is missing.")
            validate_publication(value, activity.info(), expected_run, trusted_commit)
        except ValueError as error:
            raise ApplicationError(
                str(error), type="InvalidPublicationInput", non_retryable=True
            ) from error
        with tempfile.TemporaryDirectory(prefix="temporal-release-") as directory:
            input_file, output_file = (
                Path(directory) / name for name in ("input.json", "output.json")
            )
            data = json_value(value) | {
                "releaseDigest": value.release.digest(),
                "mavenArtifacts": maven_artifacts(value.release.candidate.mavenPolicy),
            }
            input_file.write_text(json.dumps(data, separators=(",", ":")))
            env = {
                "RELEASE_INPUT_FILE": str(input_file),
                "RELEASE_OUTPUT_FILE": str(output_file),
                "RELEASE_STAGE": stage,
                "TRUSTED_AUTOMATION_ROOT": str(self.trusted_root),
            }
            for name in ("GH_TOKEN", "RH_USER", "RH_PASSWORD"):
                if self.environment.get(name):
                    env[name] = self.environment[name]
            try:
                output = await run_trusted_command(
                    self.source_root,
                    self.trusted_root / ".github/scripts/temporal-release/reconcile-publication.sh",
                    env,
                )
            except subprocess.CalledProcessError as error:
                if error.returncode not in COMMAND_FAILURES:
                    raise
                message, kind = COMMAND_FAILURES[error.returncode]
                raise ApplicationError(message, type=kind, non_retryable=True) from error
            if output:
                raise RuntimeError("Publication command wrote unexpected standard output.")
            raw: Any = json.loads(output_file.read_text())
            return cast(T, value_to_type(result_type, raw))


def validate_publication(
    value: PublicationInput,
    info: activity.Info,
    expected_run: str,
    trusted_commit: str,
) -> None:
    validate_publication_input(value)
    if (
        info.workflow_id,
        info.workflow_run_id,
        info.task_queue,
        trusted_commit,
    ) != (
        candidate_workflow_id(value.release.candidate),
        expected_run,
        publication_queue(value.release, value.mavenGenerations[-1].generation),
        value.release.candidate.trustedAutomationCommit,
    ):
        raise ValueError("Publication input does not match the privileged Actions run.")


def validate_publication_input(value: PublicationInput) -> None:
    payload = value.mavenPayload
    if payload is None:
        raise ValueError("The frozen Maven payload is missing.")
    if not matches_maven_payload(value.release, payload):
        raise ValueError("The frozen Maven payload identity is invalid.")
    for generation in value.mavenGenerations:
        generation.validate()
    if not value.mavenGenerations or [item.generation for item in value.mavenGenerations] != list(
        range(len(value.mavenGenerations))
    ):
        raise ValueError("Maven generations are missing or out of order.")
