from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from datetime import timedelta
from typing import Any, TypeVar, cast

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ApplicationError
from temporalio.workflow import ActivityCancellationType, ParentClosePolicy

with workflow.unsafe.imports_passed_through():
    from .models import (
        NATIVE_PLATFORMS,
        ApprovalEvidence,
        ApprovalRequest,
        ArtifactManifest,
        CandidateIdentity,
        CandidateStatus,
        ControlEvidence,
        GithubArtifactReceipt,
        ManualMavenAttempt,
        MavenGenerationState,
        MavenInspection,
        MavenReceipt,
        OwnershipClaim,
        OwnershipStatus,
        PublicationInput,
        ReleaseIdentity,
        ReleaseResult,
        ReleaseStatus,
        github_maven_artifact_name,
        github_native_artifact_name,
        maven_artifacts,
        native_artifact_name,
        ownership_queue,
        publication_queue,
        release_queue,
        release_workflow_id,
    )

T = TypeVar("T")


@workflow.defn(name="CandidateWorkflow")
class CandidateWorkflow:
    def __init__(self) -> None:
        self.identity: CandidateIdentity | None = None
        self.artifacts: list[GithubArtifactReceipt] = []
        self.pending: list[str] = []
        self.releaseIdentity: ReleaseIdentity | None = None

    @workflow.run
    async def prepare(self, candidate: CandidateIdentity) -> ReleaseIdentity:
        candidate.validate()
        self.identity = candidate
        self.pending = list(NATIVE_PLATFORMS)
        self._memo()
        await workflow.wait_condition(lambda: not self.pending)
        self.releaseIdentity = ReleaseIdentity.create(
            candidate, ArtifactManifest(self.artifacts), workflow.info().run_id
        )
        self._memo()
        await workflow.start_child_workflow(
            "ReleaseWorkflow",
            self.releaseIdentity,
            id=release_workflow_id(self.releaseIdentity),
            task_queue=release_queue(self.releaseIdentity),
            memo={"ReleaseIdentity": self.releaseIdentity},
            parent_close_policy=ParentClosePolicy.ABANDON,
        )
        return self.releaseIdentity

    @workflow.update(name="recordArtifact")
    def record_artifact(self, platform: str, artifact: GithubArtifactReceipt) -> CandidateStatus:
        if platform in self.pending:
            self.pending.remove(platform)
            self.artifacts.append(artifact)
        self._memo()
        return self._status()

    @record_artifact.validator
    def validate_artifact(self, platform: str, artifact: GithubArtifactReceipt) -> None:
        if self.identity is None:
            raise RuntimeError("Candidate is not waiting for this native platform.")
        artifact.validate()
        expected = native_artifact_name(self.identity.version, platform)
        valid = github_native_artifact_name(self.identity, platform) == artifact.artifactName
        valid = valid and len(artifact.files) == 1 and artifact.files[0].name == expected
        if not valid:
            raise ValueError("GitHub artifact does not match the native platform.")
        if platform not in self.pending and not any(
            old.artifactName == artifact.artifactName and old.canonical() == artifact.canonical()
            for old in self.artifacts
        ):
            raise RuntimeError("Candidate already recorded another native artifact.")

    @workflow.query(name="candidate")
    def candidate(self) -> CandidateIdentity | None:
        return self.identity

    def _status(self) -> CandidateStatus:
        return CandidateStatus(
            self.identity, list(self.pending), list(self.artifacts), self.releaseIdentity
        )

    def _memo(self) -> None:
        workflow.upsert_memo({"CandidateStatus": self._status()})


@workflow.defn(name="ReleaseOwnershipWorkflow")
class ReleaseOwnershipWorkflow:
    def __init__(self) -> None:
        self.value: OwnershipStatus | None = None

    @workflow.run
    async def manage(self, claim: OwnershipClaim) -> None:
        claim.validate()
        self.value = OwnershipStatus.from_claim(claim, int(workflow.now().timestamp() * 1000))
        self._memo()
        await workflow.wait_condition(lambda: False)

    @workflow.update(name="claim")
    def claim(self, claim: OwnershipClaim) -> OwnershipStatus:
        assert self.value is not None
        if self.value.owner == "MANUAL" and claim.owner == "TEMPORAL":
            return self.value
        updated = OwnershipStatus.from_claim(claim, int(workflow.now().timestamp() * 1000))
        if self.value.owner == claim.owner == "MANUAL":
            updated.manualMavenState = self.value.manualMavenState
            updated.manualMavenActor = self.value.manualMavenActor
            updated.manualMavenRunId = self.value.manualMavenRunId
        self.value = updated
        self._memo()
        return updated

    @claim.validator
    def validate_claim(self, claim: OwnershipClaim) -> None:
        claim.validate()
        if self.value is None:
            raise RuntimeError("Ownership Workflow is not initialized.")
        if self.value.tag != claim.tag or self.value.commitSha != claim.commitSha:
            raise ValueError("The release tag is already owned by another commit.")
        if (
            self.value.owner == "TEMPORAL"
            and claim.owner == "MANUAL"
            and (not claim.handoffConfirmed or self.value.releaseDigest != claim.releaseDigest)
        ):
            raise ValueError("Manual takeover requires the exact Temporal handoff result.")
        same = self.value.releaseDigest == claim.releaseDigest
        unspecified = (
            claim.owner == "MANUAL" and not self.value.releaseDigest and bool(claim.releaseDigest)
        )
        if self.value.owner == claim.owner and not (same or unspecified):
            raise ValueError("The ownership release digest cannot change.")

    @workflow.update(name="recordManualMaven")
    def record_manual_maven(self, attempt: ManualMavenAttempt) -> OwnershipStatus:
        assert self.value is not None
        self.value.manualMavenState = attempt.state
        self.value.manualMavenActor = attempt.githubActor
        self.value.manualMavenRunId = attempt.githubRunId
        self.value.recordedAtMillis = int(workflow.now().timestamp() * 1000)
        self._memo()
        return self.value

    @record_manual_maven.validator
    def validate_manual_maven(self, attempt: ManualMavenAttempt) -> None:
        attempt.validate()
        value = self.value
        if (
            value is None
            or value.owner != "MANUAL"
            or (value.tag, value.commitSha, value.releaseDigest)
            != (attempt.tag, attempt.commitSha, attempt.releaseDigest)
        ):
            raise ValueError("Manual Maven evidence does not match the durable release ownership.")
        if attempt.state == "STARTED":
            if value.manualMavenState != "NOT_STARTED":
                raise RuntimeError(
                    "Manual Maven publication already started; inspect remote state before continuing."
                )
        elif value.manualMavenState != "STARTED" or (attempt.githubActor, attempt.githubRunId) != (
            value.manualMavenActor,
            value.manualMavenRunId,
        ):
            raise RuntimeError(
                "Only the exact GitHub run that started manual Maven publication may complete it."
            )

    @workflow.query(name="status")
    def status(self) -> OwnershipStatus | None:
        return self.value

    def _memo(self) -> None:
        workflow.upsert_memo({"ReleaseOwnership": self.value})


@workflow.defn(name="ReleaseWorkflow")
class ReleaseWorkflow:
    def __init__(self) -> None:
        self.s = ReleaseStatus("INITIALIZING")
        self.pauseRequested = False
        self.handoffRequested = False
        self.activeActivity: asyncio.Task[Any] | None = None

    @workflow.run
    async def release(self, identity: ReleaseIdentity) -> ReleaseResult:
        identity.validate()
        self.s.identity = identity
        self.s.ownership = await self._ownership("claimTemporal", identity, OwnershipStatus)
        if self.s.ownership.owner == "MANUAL":
            self.s.handedOffFrom = "INITIALIZING"
            self._enter_handed_off()
            return await self._await_handoff()
        await self._await_approval()
        if self.handoffRequested:
            self._enter_handed_off()
            return await self._await_handoff()
        await self._await_maven_payload()
        if self.handoffRequested:
            return await self._await_handoff()
        await self._run_stage("PREFLIGHT", lambda: self._publication("preflight", None))
        if self.handoffRequested:
            return await self._await_handoff()
        await self._run_maven()
        if self.handoffRequested:
            return await self._await_handoff()

        async def draft() -> None:
            self.s.githubDraftUrl = await self._publication("reconcileGithubDraft", str)

        await self._run_stage("GITHUB_DRAFT", draft)
        result: ReleaseResult | None = None

        async def publish() -> None:
            nonlocal result
            result = await self._publication(
                "publishGithubRelease", ReleaseResult, self.s.mavenCentralUrl
            )

        await self._run_stage("PUBLISH_GITHUB", publish)
        if self.handoffRequested:
            self._enter_handed_off()
            return await self._await_handoff()
        assert result is not None
        self.s.phase, self.s.githubReleaseUrl = "PUBLISHED", result.githubReleaseUrl
        self._memo()
        return result

    @workflow.update(name="requestApproval")
    def request_approval(self, request: ApprovalRequest) -> ReleaseStatus:
        self.s.approvalRequest = request
        self._memo()
        return self.status()

    @request_approval.validator
    def validate_approval_request(self, request: ApprovalRequest) -> None:
        if (
            self.s.identity is None
            or self.s.phase != "AWAITING_APPROVAL"
            or self.s.approvalRequest is not None
        ):
            raise RuntimeError("The release cannot accept an approval request.")
        request.validate()
        self._validate_execution(request.releaseDigest, request.workflowId, request.runId)
        if self.s.identity.candidate.trustedAutomationCommit != request.trustedWorkerCommit:
            raise ValueError("Approval request uses another trusted Worker commit.")

    @workflow.update(name="approve")
    def approve(self, evidence: ApprovalEvidence) -> ReleaseStatus:
        self.s.approval = evidence
        self._memo()
        return self.status()

    @approve.validator
    def validate_approval(self, evidence: ApprovalEvidence) -> None:
        if (
            self.s.identity is None
            or self.s.phase != "AWAITING_APPROVAL"
            or self.s.approvalRequest is None
        ):
            raise RuntimeError("The release is not awaiting approval.")
        evidence.validate()
        self._validate_execution(evidence.releaseDigest, evidence.workflowId, evidence.runId)
        if not self.s.approvalRequest.matches(evidence):
            raise ValueError("Approval does not match the recorded approval request.")

    @workflow.update(name="recordMavenPayload")
    def record_maven_payload(self, artifact: GithubArtifactReceipt) -> ReleaseStatus:
        if self.s.mavenPayload is None:
            self.s.mavenPayload = artifact
        self._memo()
        return self.status()

    @record_maven_payload.validator
    def validate_maven_payload(self, artifact: GithubArtifactReceipt) -> None:
        identity = self.s.identity
        if (
            identity is None
            or self.s.approval is None
            or self.s.phase in {"PUBLISHED", "HANDED_OFF"}
        ):
            raise RuntimeError("The release cannot accept a Maven payload.")
        artifact.validate()
        if (
            github_maven_artifact_name(identity) != artifact.artifactName
            or len(artifact.files) != 1
            or artifact.files[0].name != "maven-payload.tar"
        ):
            raise ValueError("The Maven GitHub artifact identity is invalid.")
        if self.s.mavenPayload and self.s.mavenPayload.canonical() != artifact.canonical():
            raise RuntimeError("The release already recorded another Maven payload.")

    @workflow.update(name="control")
    async def control(self, evidence: ControlEvidence) -> ReleaseStatus:
        self.s.control = evidence
        evidence.recordedAtMillis = int(workflow.now().timestamp() * 1000)
        if evidence.action == "pause":
            self.pauseRequested = True
            self._begin_quiescing()
            self._cancel_activity()
            await workflow.wait_condition(lambda: self.s.phase in {"PAUSED", "HANDED_OFF"})
        elif evidence.action == "resume":
            self.pauseRequested = False
            self.s.phase = self.s.pausedFrom or self.s.phase
            self.s.pausedFrom = self.s.lastError = None
            self.s.blockedAtMillis = 0
        elif evidence.action == "retry-maven-submission":
            next_generation = evidence.mavenSubmissionGeneration > self.s.mavenSubmissionGeneration
            assert evidence.mavenInspection is not None
            self._adopt_inspection(evidence.mavenInspection)
            self.s.mavenSubmissionGeneration = evidence.mavenSubmissionGeneration
            self.s.mavenRetryAuthorization = evidence
            self.s.phase = (
                "MAVEN_REPOSITORY" if next_generation else self.s.pausedFrom or self.s.phase
            )
            self.s.pausedFrom = self.s.lastError = None
            self.s.blockedAtMillis = 0
        else:
            self.handoffRequested = True
            self.pauseRequested = False
            self._begin_quiescing()
            self._cancel_activity()
            if self.activeActivity is None:
                self._enter_handed_off()
            await workflow.wait_condition(lambda: self.s.phase == "HANDED_OFF")
            assert self.s.identity is not None
            self.s.ownership = await self._ownership(
                "handoffManual", self.s.identity, OwnershipStatus, evidence
            )
            if self.s.ownership.owner != "MANUAL":
                raise RuntimeError("Temporal ownership handoff did not complete.")
        self._memo()
        return self.status()

    @control.validator
    def validate_control(self, evidence: ControlEvidence) -> None:
        identity = self.s.identity
        if identity is None or self.s.phase in {"PUBLISHED", "HANDED_OFF"}:
            raise RuntimeError("The release is not controllable.")
        evidence.validate()
        self._validate_execution(evidence.releaseDigest, evidence.workflowId, evidence.runId)
        if (identity.candidate.tag, identity.candidate.commitSha) != (
            evidence.tag,
            evidence.commitSha,
        ):
            raise ValueError("Control evidence does not match the exact tag and SHA.")
        if evidence.action == "resume" and self.s.phase not in {"PAUSED", "BLOCKED"}:
            raise RuntimeError("Only a paused or blocked release can resume.")
        if evidence.action == "retry-maven-submission":
            self._validate_maven_retry(evidence)
        elif evidence.action == "handoff-manual":
            self.validate_manual_handoff(
                self.s.mavenGenerations, self.s.mavenCentralUrl, evidence.manualMavenRequested
            )

    @workflow.query(name="status")
    def status(self) -> ReleaseStatus:
        return self.s

    def _validate_maven_retry(self, evidence: ControlEvidence) -> None:
        assert self.s.identity and evidence.mavenInspection
        if evidence.mavenInspection.centralPresent + evidence.mavenInspection.centralMissing != len(
            maven_artifacts(self.s.identity.candidate.mavenPolicy)
        ):
            raise ValueError("Maven inspection does not match the release policy.")
        self.validate_inspected_generations(self.s.mavenGenerations, evidence.mavenInspection)
        blocked = (
            self.s.phase == "BLOCKED"
            and bool(self.s.pausedFrom and self.s.pausedFrom.startswith("MAVEN_"))
            and bool(self.s.lastError)
        )
        next_gen = (
            blocked
            and any(
                x in (self.s.lastError or "")
                for x in ("MavenSubmissionAmbiguous", "MavenDeploymentFailed")
            )
            and evidence.mavenSubmissionGeneration == self.s.mavenSubmissionGeneration + 1
        )
        replace = (
            blocked
            and "InvalidApproval" in (self.s.lastError or "")
            and self.s.mavenSubmissionGeneration > 0
            and evidence.mavenSubmissionGeneration == self.s.mavenSubmissionGeneration
        )
        if not (next_gen or replace):
            raise RuntimeError(
                "Maven authorization must advance an ambiguous attempt or replace stale evidence."
            )
        if next_gen and evidence.mavenInspection.centralPresent != 0:
            raise RuntimeError("A new Maven generation requires Central to be completely absent.")
        if next_gen:
            for item in evidence.mavenInspection.generations:
                failed = item.portalDeploymentState == "FAILED"
                if not (
                    item.repositoryState == "absent"
                    or failed
                    and item.repositoryState == "released"
                ) or item.portalDeploymentState not in {"", "FAILED"}:
                    raise RuntimeError(
                        "A new Maven generation requires every earlier attempt to be inactive."
                    )

    @staticmethod
    def validate_manual_handoff(
        generations: list[MavenGenerationState], central: str | None, manual: bool
    ) -> None:
        started, complete = any(x.submissionStarted for x in generations), bool(central)
        if manual and started:
            raise RuntimeError(
                "Manual Maven publication cannot take over after automatic Maven submission started."
            )
        if not manual and started and not complete:
            raise RuntimeError(
                "Manual non-Maven takeover requires automatic Maven publication to be complete."
            )

    @staticmethod
    def validate_inspected_generations(
        durable: list[MavenGenerationState], inspection: MavenInspection
    ) -> None:
        if {x.generation for x in durable} != {x.generation for x in inspection.generations} or len(
            durable
        ) != len(inspection.generations):
            raise ValueError("The Maven inspection does not cover every durable generation.")

    async def _await_approval(self) -> None:
        self.s.phase = "AWAITING_APPROVAL"
        self._memo()
        while self.s.approval is None and not self.handoffRequested:
            await self._handle_pause("AWAITING_APPROVAL")
            await workflow.wait_condition(
                lambda: self.s.approval is not None or self.pauseRequested or self.handoffRequested
            )

    async def _await_maven_payload(self) -> None:
        while self.s.mavenPayload is None and not self.handoffRequested:
            await self._handle_pause("AWAITING_MAVEN_PAYLOAD")
            self.s.phase = "AWAITING_MAVEN_PAYLOAD"
            self._memo()
            await workflow.wait_condition(
                lambda: self.s.mavenPayload is not None
                or self.pauseRequested
                or self.handoffRequested
            )

    async def _run_maven(self) -> None:
        while not self.handoffRequested:

            async def repository() -> None:
                current = self._current_generation()
                create = not current.submissionStarted
                if create:
                    current.submissionStarted = True
                    self._memo()
                current.sonatypeRepositoryId = await self._publication(
                    "reconcileMavenRepository", str, create
                )
                self.s.sonatypeRepositoryId = current.sonatypeRepositoryId
                self._memo()

            await self._run_stage("MAVEN_REPOSITORY", repository)
            if self.handoffRequested:
                return
            if self._current_generation().sonatypeRepositoryId is None:
                return
            generation = self.s.mavenSubmissionGeneration

            async def portal() -> None:
                current = self._current_generation()
                current.portalDeploymentId = await self._publication("reconcileMavenPortal", str)
                self.s.portalDeploymentId = current.portalDeploymentId
                self._memo()

            await self._run_stage("MAVEN_PORTAL", portal)
            if generation != self.s.mavenSubmissionGeneration or self.handoffRequested:
                continue
            if self._current_generation().portalDeploymentId is None:
                return

            async def publish() -> None:
                receipt = await self._publication("publishMaven", MavenReceipt)
                self.s.mavenCentralUrl, self.s.sonatypeRepositoryId, self.s.portalDeploymentId = (
                    receipt.mavenCentralUrl,
                    receipt.sonatypeRepositoryId,
                    receipt.portalDeploymentId,
                )

            await self._run_stage("MAVEN_PUBLISH", publish)
            if generation == self.s.mavenSubmissionGeneration:
                return

    async def _run_stage(self, stage: str, action: Callable[[], Awaitable[None]]) -> None:
        retry = 0
        while not self.handoffRequested:
            await self._handle_pause(stage)
            if self.handoffRequested:
                return
            self.s.phase = stage
            self.s.stageAttempt += 1
            self.s.stageStartedAtMillis = int(workflow.now().timestamp() * 1000)
            self.s.nextRetryAtMillis = 0
            self._memo()
            try:
                await action()
                self.activeActivity = None
                self.s.lastCompletedStage, self.s.lastError = stage, None
                self.s.blockedAtMillis = self.s.nextRetryAtMillis = 0
                self._memo()
                return
            except BaseException as error:
                self.activeActivity = None
                if self.handoffRequested:
                    self._enter_handed_off()
                    return
                if not self.pauseRequested and self._non_retryable(error):
                    self.s.pausedFrom, self.s.phase = stage, "BLOCKED"
                    self.s.lastError = self._safe_failure(error)
                    self.s.blockedAtMillis = int(workflow.now().timestamp() * 1000)
                    self._memo()
                    await workflow.wait_condition(
                        lambda: self.s.phase != "BLOCKED" or self.handoffRequested
                    )
                    if self.s.phase != stage:
                        return
                elif not self.pauseRequested:
                    self.s.lastError = self._safe_failure(error)
                    delay = min(15, 2 << min(retry, 3))
                    retry += 1
                    self.s.nextRetryAtMillis = (
                        int(workflow.now().timestamp() * 1000) + delay * 60_000
                    )
                    self._memo()
                    try:
                        await workflow.wait_condition(
                            lambda: self.pauseRequested or self.handoffRequested,
                            timeout=timedelta(minutes=delay),
                        )
                    except TimeoutError:
                        pass

    async def _handle_pause(self, resume: str) -> None:
        if not self.pauseRequested:
            return
        self.s.pausedFrom, self.s.phase = resume, "PAUSED"
        self._memo()
        await workflow.wait_condition(lambda: not self.pauseRequested or self.handoffRequested)
        if not self.handoffRequested:
            self.s.phase, self.s.pausedFrom = resume, None
            self._memo()

    async def _publication(self, name: str, result_type: type[T] | None, *args: Any) -> T:
        assert self.s.identity and self.s.approvalRequest and self.s.approval
        params = [self._publication_input(), *args]
        if name == "publishGithubRelease":
            params = [self._publication_input(), *args]
        self.activeActivity = workflow.start_activity(
            name,
            args=params,
            result_type=result_type,
            task_queue=publication_queue(self.s.identity, self.s.mavenSubmissionGeneration),
            start_to_close_timeout=timedelta(minutes=90),
            heartbeat_timeout=timedelta(minutes=1),
            cancellation_type=ActivityCancellationType.WAIT_CANCELLATION_COMPLETED,
            retry_policy=RetryPolicy(
                initial_interval=timedelta(minutes=2),
                maximum_interval=timedelta(minutes=15),
                maximum_attempts=1,
                non_retryable_error_types=["ReleaseIdentityConflict", "InvalidApproval"],
            ),
        )
        return cast(T, await self.activeActivity)

    async def _ownership(self, name: str, first: Any, result_type: type[T], *args: Any) -> T:
        assert self.s.identity
        return cast(
            T,
            await workflow.execute_activity(
                name,
                args=[first, *args],
                result_type=result_type,
                task_queue=ownership_queue(self.s.identity.candidate.tag),
                start_to_close_timeout=timedelta(minutes=2),
                retry_policy=RetryPolicy(
                    initial_interval=timedelta(seconds=10), maximum_interval=timedelta(minutes=2)
                ),
            ),
        )

    def _publication_input(self) -> PublicationInput:
        assert self.s.identity and self.s.approvalRequest and self.s.approval
        info = workflow.info()
        return PublicationInput(
            self.s.identity,
            self.s.approvalRequest,
            self.s.approval,
            info.workflow_id,
            info.run_id,
            self.s.mavenSubmissionGeneration,
            self.s.mavenRetryAuthorization,
            self.s.mavenPayload,
            list(self.s.mavenGenerations),
        )

    def _current_generation(self) -> MavenGenerationState:
        assert self.s.identity
        for item in self.s.mavenGenerations:
            if item.generation == self.s.mavenSubmissionGeneration:
                item.validate(self.s.identity.digest())
                return item
        item = MavenGenerationState.create(
            self.s.identity.digest(), self.s.mavenSubmissionGeneration
        )
        self.s.mavenGenerations.append(item)
        self._memo()
        return item

    def _adopt_inspection(self, inspection: MavenInspection) -> None:
        assert self.s.identity
        for found in inspection.generations:
            for durable in self.s.mavenGenerations:
                if durable.generation != found.generation:
                    continue
                if not durable.sonatypeRepositoryId:
                    durable.sonatypeRepositoryId = found.repositoryId
                elif found.repositoryId and durable.sonatypeRepositoryId != found.repositoryId:
                    raise ValueError("Inspected Sonatype repository ID differs.")
                if not durable.portalDeploymentId:
                    durable.portalDeploymentId = found.portalDeploymentId
                elif (
                    found.portalDeploymentId
                    and durable.portalDeploymentId != found.portalDeploymentId
                ):
                    raise ValueError("Inspected Portal deployment ID differs.")
                durable.validate(self.s.identity.digest())

    def _validate_execution(self, digest: str, workflow_id: str, run_id: str) -> None:
        assert self.s.identity
        info = workflow.info()
        if (digest, workflow_id, run_id) != (
            self.s.identity.digest(),
            info.workflow_id,
            info.run_id,
        ):
            raise ValueError("Evidence does not identify this exact release run.")

    def _begin_quiescing(self) -> None:
        if self.activeActivity is not None:
            if self.s.pausedFrom is None:
                self.s.pausedFrom = self.s.phase
            self.s.phase = "QUIESCING"
            self._memo()
        elif self.handoffRequested:
            self._enter_handed_off()
        elif self.s.phase != "PAUSED":
            self.s.pausedFrom, self.s.phase = self.s.phase, "PAUSED"
            self._memo()

    def _enter_handed_off(self) -> None:
        if self.s.handedOffFrom is None:
            self.s.handedOffFrom = self.s.pausedFrom or self.s.phase
        self.s.phase, self.s.pausedFrom = "HANDED_OFF", None
        self._memo()

    def _cancel_activity(self) -> None:
        if self.activeActivity is not None:
            self.activeActivity.cancel()

    @staticmethod
    def _safe_failure(error: BaseException) -> str:
        current: BaseException | None = error
        while current:
            if isinstance(current, ApplicationError):
                return f"{current.type}: {current.message}"
            current = current.__cause__
        return f"{type(error).__name__}: {error}"

    @staticmethod
    def _non_retryable(error: BaseException) -> bool:
        current: BaseException | None = error
        while current:
            if isinstance(current, ApplicationError):
                return current.non_retryable
            current = current.__cause__
        return False

    async def _await_handoff(self) -> ReleaseResult:
        await workflow.wait_condition(lambda: False)
        raise RuntimeError("A handed-off release cannot resume automatically.")

    def _memo(self) -> None:
        workflow.upsert_memo({"ReleaseStatus": self.s})
