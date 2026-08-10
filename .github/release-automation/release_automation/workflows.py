from datetime import timedelta
from typing import TypeVar, cast

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ApplicationError

with workflow.unsafe.imports_passed_through():
    from .models import (
        NATIVE_PLATFORMS,
        CandidateIdentity,
        GithubArtifactReceipt,
        MavenGeneration,
        MavenInspection,
        PublicationInput,
        ReleaseIdentity,
        ReleaseResult,
        ReleaseStatus,
        github_native_artifact_name,
        matches_maven_payload,
        maven_artifacts,
        native_artifact_name,
        publication_queue,
    )

T = TypeVar("T")


@workflow.defn(name="ReleaseWorkflow", failure_exception_types=[Exception])
class ReleaseWorkflow:
    def __init__(self) -> None:
        """Initialize queryable state before the immutable candidate is supplied."""
        self.s = ReleaseStatus("INITIALIZING")
        self.candidate: CandidateIdentity | None = None

    @workflow.run
    async def release(self, candidate: CandidateIdentity) -> ReleaseResult:
        """Drive one candidate from native builds through Maven and GitHub publication.

        The Workflow stores only durable identities and external observations. Binary
        payloads remain in GitHub Actions artifacts, while short-lived Workers can stop
        and resume without losing which exact release or Maven generation they own.
        """
        candidate.validate()
        self.candidate = candidate
        self.s.pendingPlatforms = list(NATIVE_PLATFORMS)
        self.s.phase = "AWAITING_NATIVE_ARTIFACTS"
        self._memo()
        await workflow.wait_condition(lambda: not self.s.pendingPlatforms)
        self._freeze_identity()
        self._memo()
        await workflow.wait_condition(lambda: self.s.mavenPayload is not None)
        return await self._publish()

    @workflow.update(name="recordArtifact")
    def record_artifact(self, platform: str, artifact: GithubArtifactReceipt) -> ReleaseStatus:
        """Idempotently record one immutable native artifact receipt."""
        if platform in self.s.pendingPlatforms:
            self.s.pendingPlatforms.remove(platform)
            self.s.artifacts.append(artifact)
        self._freeze_identity()
        self._memo()
        return self.s

    @record_artifact.validator
    def validate_artifact(self, platform: str, artifact: GithubArtifactReceipt) -> None:
        """Reject wrong-platform receipts and conflicting repeat deliveries."""
        candidate = self.candidate
        if candidate is None or self.s.identity is not None:
            raise RuntimeError("Release is not waiting for this native platform.")
        valid = github_native_artifact_name(candidate, platform) == artifact.artifactName
        valid &= artifact.fileName == native_artifact_name(candidate.version, platform)
        if not valid:
            raise ValueError("GitHub artifact does not match the native platform.")
        if platform not in self.s.pendingPlatforms and not any(
            old.artifactName == artifact.artifactName and old == artifact
            for old in self.s.artifacts
        ):
            raise RuntimeError("Release already recorded another native artifact.")

    @workflow.update(name="recordMavenPayload")
    def record_maven_payload(self, artifact: GithubArtifactReceipt) -> ReleaseStatus:
        """Idempotently freeze the single signed Maven payload receipt."""
        if self.s.mavenPayload is None:
            self.s.mavenPayload = artifact
        self._memo()
        return self.s

    @record_maven_payload.validator
    def validate_maven_payload(self, artifact: GithubArtifactReceipt) -> None:
        """Accept only the Maven artifact derived from the frozen release identity."""
        identity = self.s.identity
        if identity is None or self.s.phase == "PUBLISHED":
            raise RuntimeError("The release cannot accept a Maven payload.")
        if not matches_maven_payload(identity, artifact):
            raise ValueError("The Maven GitHub artifact identity is invalid.")
        if self.s.mavenPayload and self.s.mavenPayload != artifact:
            raise RuntimeError("The release already recorded another Maven payload.")

    @workflow.query(name="status")
    def status(self) -> ReleaseStatus:
        """Return the complete operator-visible durable release snapshot."""
        return self.s

    async def _publish(self) -> ReleaseResult:
        """Publish, reconciling ambiguous Maven outcomes before any new submission.

        An Activity failure that is merely transient is handled by Activity retries.
        Only typed ambiguity or deployment failure enters generation recovery, ensuring
        a second repository is never created simply because a network response was lost.
        """
        while True:
            generation = self._current_generation()
            generation.submissionStarted = True
            self.s.phase = "PUBLISHING"
            self._memo()
            try:
                result = await self._publication("publishRelease", ReleaseResult)
            except BaseException as error:
                if self._error_type(error) in {
                    "MavenSubmissionAmbiguous",
                    "MavenDeploymentFailed",
                }:
                    await self._recover_maven(error)
                    continue
                raise
            self.s.phase = "PUBLISHED"
            self._memo()
            return result

    async def _recover_maven(self, cause: BaseException) -> None:
        """Prove a prior Maven generation inactive before permitting one replacement.

        Two inspections separated by durable Workflow time guard against eventually
        consistent Sonatype repository creation. A visible or progressing repository is
        retained; only repeated evidence of absence, or a terminal failed Portal
        deployment whose repository is released, can advance to the single replacement.
        """
        assert self.s.identity
        for final in (False, True):
            inspection = await self._publication("inspectMaven", MavenInspection)
            self._adopt_inspection(inspection)
            current = inspection.generations[-1]
            if inspection.centralPresent == len(
                maven_artifacts(self.s.identity.candidate.mavenPolicy)
            ):
                self._memo()
                return
            if current.repositoryState in {"open", "closed"} or current.portalDeploymentState in {
                "PENDING",
                "VALIDATING",
                "VALIDATED",
                "PUBLISHING",
                "PUBLISHED",
            }:
                return
            retryable = not inspection.centralPresent and all(
                (item.repositoryState, item.portalDeploymentState)
                in {("absent", ""), ("released", "FAILED")}
                for item in inspection.generations
            )
            if not retryable or final:
                break
            await workflow.sleep(timedelta(minutes=10))
        if not retryable or len(self.s.mavenGenerations) >= 2:
            raise cause
        self.s.mavenGenerations.append(MavenGeneration(len(self.s.mavenGenerations)))
        self._memo()

    async def _publication(self, name: str, result_type: type[T]) -> T:
        """Execute a privileged Activity on the exact release-generation queue."""
        assert self.s.identity
        return cast(
            T,
            await workflow.execute_activity(
                name,
                arg=PublicationInput(
                    self.s.identity, self.s.mavenPayload, list(self.s.mavenGenerations)
                ),
                result_type=result_type,
                task_queue=publication_queue(
                    self.s.identity, self.s.mavenGenerations[-1].generation
                ),
                start_to_close_timeout=timedelta(minutes=90),
                heartbeat_timeout=timedelta(minutes=1),
                retry_policy=RetryPolicy(
                    initial_interval=timedelta(minutes=2),
                    maximum_interval=timedelta(minutes=15),
                    non_retryable_error_types=[
                        "ReleaseIdentityConflict",
                        "InvalidPublicationInput",
                        "MavenSubmissionAmbiguous",
                        "MavenDeploymentFailed",
                    ],
                ),
            ),
        )

    def _current_generation(self) -> MavenGeneration:
        """Create generation zero lazily and return the validated current generation."""
        assert self.s.identity
        if not self.s.mavenGenerations:
            self.s.mavenGenerations.append(MavenGeneration(0))
            self._memo()
        item = self.s.mavenGenerations[-1]
        item.validate()
        return item

    def _adopt_inspection(self, inspection: MavenInspection) -> None:
        """Merge newly observed external IDs without allowing durable identity changes.

        Repository and Portal IDs may become visible after an ambiguous response, so an
        empty durable field can adopt an inspected value. Once adopted, a differing value
        is an immutable conflict rather than a recoverable observation.
        """
        assert self.s.identity
        inspection.validate()
        if inspection.centralPresent > len(maven_artifacts(self.s.identity.candidate.mavenPolicy)):
            raise ValueError("Maven inspection does not match the release policy.")
        if [x.generation for x in self.s.mavenGenerations] != [
            x.generation for x in inspection.generations
        ]:
            raise ValueError("Maven inspection does not cover every durable generation.")
        durable = {item.generation: item for item in self.s.mavenGenerations}
        for found in inspection.generations:
            state = durable[found.generation]
            for field, inspected in (
                ("repositoryId", found.repositoryId),
                ("portalDeploymentId", found.portalDeploymentId),
            ):
                current = getattr(state, field)
                if current and inspected and current != inspected:
                    raise ValueError("Inspected Maven identity differs from durable state.")
                if not current:
                    setattr(state, field, inspected)
            state.validate()

    @staticmethod
    def _error_type(error: BaseException) -> str:
        """Find the Temporal ApplicationError type through wrapper exception causes."""
        current: BaseException | None = error
        while current:
            if isinstance(current, ApplicationError):
                return current.type or ""
            current = current.__cause__
        return ""

    def _memo(self) -> None:
        """Expose durable state to scheduled discovery even when no Worker is running."""
        workflow.upsert_memo({"ReleaseStatus": self.s})

    def _freeze_identity(self) -> None:
        """Freeze the release exactly once when the final native receipt arrives.

        This transition occurs inside the update handler as well as the main coroutine so
        the memo cannot be stranded between phases when its short-lived update Worker
        exits immediately after acknowledging the final artifact.
        """
        if not self.s.pendingPlatforms and self.s.identity is None:
            assert self.candidate
            self.s.identity = ReleaseIdentity(self.candidate, self.s.artifacts)
            self.s.phase = "AWAITING_MAVEN_PAYLOAD"
