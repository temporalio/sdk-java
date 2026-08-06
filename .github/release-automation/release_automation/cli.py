from __future__ import annotations

import asyncio
import json
import os
import re
import subprocess
import sys
import time
from collections.abc import Callable, Coroutine, Mapping
from pathlib import Path
from typing import Any, TypeVar, cast

from temporalio.client import Client, WorkflowExecution
from temporalio.common import WorkflowIDConflictPolicy, WorkflowIDReusePolicy
from temporalio.converter import value_to_type
from temporalio.exceptions import ApplicationError
from temporalio.worker import Worker

from .activities import (
    OwnershipActivities,
    PublicationActivities,
    claim_ownership,
    ownership_handle,
)
from .models import (
    PLATFORMS,
    ApprovalEvidence,
    ApprovalRequest,
    CandidateIdentity,
    CandidateStatus,
    ControlEvidence,
    DiscoveryJob,
    GithubArtifactReceipt,
    ManualMavenAttempt,
    MavenInspection,
    OwnershipClaim,
    OwnershipStatus,
    PublicationInput,
    ReleaseIdentity,
    ReleaseStatus,
    build_queue_from_digest,
    candidate_queue,
    candidate_queue_from_digest,
    candidate_workflow_id,
    json_value,
    maven_artifacts,
    maven_policy_for_projects,
    ownership_queue,
    platform_spec,
    publication_queue,
    release_queue,
    release_workflow_id,
    sha256,
)
from .workflows import CandidateWorkflow, ReleaseOwnershipWorkflow, ReleaseWorkflow

T = TypeVar("T")
ROOT = Path(__file__).resolve().parents[3]


def output(name: str, value: Any) -> None:
    text = str(value).replace("\n", " ") if value is not None else ""
    destination = os.environ.get("GITHUB_OUTPUT")
    if destination:
        with Path(destination).open("a") as stream:
            stream.write(f"{name}={text}\n")
    else:
        print(f"{name}={text}")


def required(env: Mapping[str, str], name: str) -> str:
    value = env.get(name)
    if not value:
        raise ValueError(f"Required Actions value is missing: {name}")
    return value


def read(path: str | Path, kind: type[T]) -> T:
    return cast(T, value_to_type(kind, json.loads(Path(path).read_text())))


async def connect(env: Mapping[str, str]) -> Client:
    address = required_temporal(env, "TEMPORAL_ADDRESS")
    namespace = required_temporal(env, "TEMPORAL_NAMESPACE")
    api_key = required_temporal(env, "TEMPORAL_API_KEY")
    return await Client.connect(address, namespace=namespace, api_key=api_key, tls=True)


def required_temporal(env: Mapping[str, str], name: str) -> str:
    value = env.get(name)
    if not value:
        raise ValueError(f"Required Temporal Cloud setting is missing: {name}")
    return value


def candidate_outputs(candidate: CandidateIdentity) -> None:
    candidate.validate()
    for name, value in (
        ("candidate_digest", candidate.digest()),
        ("automation_commit", candidate.trustedAutomationCommit),
        ("commit_sha", candidate.commitSha),
        ("notes_sha256", candidate.releaseNotesSha256),
        ("tag", candidate.tag),
        ("version", candidate.version),
        ("maven_policy", candidate.mavenPolicy),
    ):
        output(name, value)


def maven_policy(settings: Path) -> None:
    projects = [
        match.group(1)
        for line in settings.read_text().splitlines()
        if (match := re.fullmatch(r"include ['\"]([^'\"]+)['\"]", line))
    ]
    policy = maven_policy_for_projects(projects)
    output("maven_policy", policy)
    output("maven_artifacts_json", json.dumps(maven_artifacts(policy), separators=(",", ":")))


async def list_workflows(
    client: Client, workflow_type: str, open_only: bool = True
) -> list[WorkflowExecution]:
    query = (
        "ExecutionStatus = 'Running' AND " if open_only else ""
    ) + f"WorkflowType = '{workflow_type}'"
    return [item async for item in client.list_workflows(query)]


async def memo(execution: WorkflowExecution, key: str, kind: type[T]) -> T | None:
    return await execution.memo_value(key, None, type_hint=kind)


async def start_candidate(client: Client, candidate: CandidateIdentity) -> None:
    candidate.validate()
    for execution in await list_workflows(client, "CandidateWorkflow", False):
        existing = await memo(execution, "CandidateIdentity", CandidateIdentity)
        if existing and existing.tag == candidate.tag and existing.digest() != candidate.digest():
            raise RuntimeError("The release tag already identifies another immutable candidate.")
    handle = await client.start_workflow(
        "CandidateWorkflow",
        candidate,
        id=candidate_workflow_id(candidate),
        task_queue=candidate_queue(candidate),
        id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
        id_conflict_policy=WorkflowIDConflictPolicy.USE_EXISTING,
        memo={"CandidateIdentity": candidate},
    )
    description = await handle.describe()
    history = await handle.fetch_history()
    if not history.events or not history.events[0].HasField(
        "workflow_execution_started_event_attributes"
    ):
        raise RuntimeError("Candidate Workflow has no immutable start event.")
    started = history.events[0].workflow_execution_started_event_attributes
    if len(started.input.payloads) != 1:
        raise RuntimeError("Candidate Workflow start input is not exact.")
    decoded = (await client.data_converter.decode(started.input.payloads, [CandidateIdentity]))[0]
    started_memo = await description.memo_value("CandidateIdentity", type_hint=CandidateIdentity)
    validate_candidate_start(description, started, started_memo, decoded, candidate)
    for name, value in (
        ("workflow_id", handle.id),
        ("run_id", handle.result_run_id),
        ("candidate_digest", candidate.digest()),
        ("task_queue", candidate_queue(candidate)),
    ):
        output(name, value)


def validate_candidate_start(
    description: Any,
    started: Any,
    identity: CandidateIdentity,
    actual: CandidateIdentity,
    expected: CandidateIdentity,
) -> None:
    expected.validate()
    actual.validate()
    identity.validate()
    valid = description.id == candidate_workflow_id(expected) and bool(
        re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", description.run_id)
    )
    valid = (
        valid
        and description.workflow_type == "CandidateWorkflow"
        and description.task_queue == candidate_queue(expected)
    )
    valid = (
        valid
        and expected.canonical() == identity.canonical()
        and started.workflow_type.name == "CandidateWorkflow"
    )
    valid = (
        valid
        and started.task_queue.name == candidate_queue(expected)
        and expected.canonical() == actual.canonical()
    )
    if not valid:
        raise RuntimeError(
            "Candidate Workflow start receipt does not match the immutable candidate."
        )


async def run_one_shot_worker(
    client: Client,
    queue: str,
    operation: Callable[[], Coroutine[Any, Any, T]],
    ownership_tag: str | None = None,
) -> T:
    workers = [
        Worker(
            client,
            task_queue=queue,
            workflows=[CandidateWorkflow]
            if queue.startswith("sdk-java-release-candidate-")
            else [ReleaseWorkflow],
        )
    ]
    if ownership_tag:
        ownership = OwnershipActivities(client)
        workers.append(
            Worker(
                client,
                task_queue=ownership_queue(ownership_tag),
                workflows=[ReleaseOwnershipWorkflow],
                activities=[ownership.claim_temporal, ownership.handoff_manual],
            )
        )
    tasks = [asyncio.create_task(worker.run()) for worker in workers]
    try:
        return await operation()
    finally:
        await asyncio.gather(*(worker.shutdown() for worker in workers))
        await asyncio.gather(*tasks)


async def record_artifact(
    client: Client, workflow_id: str, run_id: str, platform: str, artifact: GithubArtifactReceipt
) -> None:
    if not workflow_id.startswith("sdk-java-release-candidate/") or not run_id:
        raise ValueError("Candidate Workflow execution is invalid.")
    artifact.validate()
    queue = candidate_queue_from_digest(workflow_id.split("/", 1)[1])

    async def update() -> CandidateStatus:
        return cast(
            CandidateStatus,
            await client.get_workflow_handle(workflow_id, run_id=run_id).execute_update(
                "recordArtifact", args=[platform, artifact], result_type=CandidateStatus
            ),
        )

    status = await run_one_shot_worker(client, queue, update)
    output("pending_platforms", len(status.pendingPlatforms))


async def status_for(execution: WorkflowExecution) -> ReleaseStatus | None:
    return await memo(execution, "ReleaseStatus", ReleaseStatus)


async def validate_release(execution: WorkflowExecution) -> ReleaseStatus:
    status = await status_for(execution)
    if status is None or status.identity is None:
        raise RuntimeError("Release execution has no immutable status identity.")
    status.identity.validate()
    validate_release_parent(execution, status.identity)
    if execution.id != release_workflow_id(
        status.identity
    ) or execution.task_queue != release_queue(status.identity):
        raise RuntimeError("Release status identity does not match Workflow routing.")
    for approval in (status.approvalRequest, status.approval):
        if approval:
            approval.validate()
            if execution.run_id != approval.runId:
                raise RuntimeError("Approval is bound to another Workflow run.")
    return status


def validate_release_parent(execution: WorkflowExecution, identity: ReleaseIdentity) -> None:
    expected = candidate_workflow_id(identity.candidate)
    if not identity.candidateRunId or (
        execution.parent_id,
        execution.parent_run_id,
        execution.root_id,
        execution.root_run_id,
    ) != (expected, identity.candidateRunId, expected, identity.candidateRunId):
        raise RuntimeError(
            "Release execution is not the child of its immutable Candidate Workflow."
        )


async def valid_release(execution: WorkflowExecution) -> ReleaseStatus | None:
    try:
        return await validate_release(execution)
    except (RuntimeError, ValueError) as error:
        print(f"Skipping malformed release execution {execution.id}: {error}", file=sys.stderr)
        return None


async def find_release(
    client: Client, tag: str, commit: str, include_closed: bool = False
) -> tuple[WorkflowExecution, ReleaseStatus]:
    matches = []
    for execution in await list_workflows(client, "ReleaseWorkflow", not include_closed):
        status = await valid_release(execution)
        if (
            status
            and status.identity
            and (status.identity.candidate.tag, status.identity.candidate.commitSha)
            == (tag, commit)
        ):
            matches.append((execution, status))
    if len(matches) != 1:
        raise RuntimeError("Tag and SHA do not identify exactly one release execution.")
    return matches[0]


async def find_release_prefer_open(
    client: Client, tag: str, commit: str
) -> tuple[WorkflowExecution, ReleaseStatus]:
    try:
        return await find_release(client, tag, commit)
    except RuntimeError:
        return await find_release(client, tag, commit, True)


async def with_release_worker(
    client: Client,
    execution: WorkflowExecution,
    identity: ReleaseIdentity,
    operation: Callable[[], Coroutine[Any, Any, T]],
) -> T:
    return await run_one_shot_worker(
        client, execution.task_queue, operation, identity.candidate.tag
    )


def write_identity(execution: WorkflowExecution, identity: ReleaseIdentity, phase: str) -> None:
    for name, value in (
        ("workflow_id", execution.id),
        ("run_id", execution.run_id),
        ("tag", identity.candidate.tag),
        ("commit_sha", identity.candidate.commitSha),
        ("notes_sha256", identity.candidate.releaseNotesSha256),
        ("manifest_sha256", identity.manifestSha256),
        ("release_digest", identity.digest()),
        ("automation_commit", identity.candidate.trustedAutomationCommit),
        ("phase", phase),
    ):
        output(name, value)


def write_status(status: ReleaseStatus) -> None:
    values = {
        "paused_from": status.pausedFrom,
        "handed_off_from": status.handedOffFrom,
        "last_completed_stage": status.lastCompletedStage,
        "last_error": status.lastError,
        "blocked_at_millis": status.blockedAtMillis,
        "maven_central_url": status.mavenCentralUrl,
        "sonatype_repository_id": status.sonatypeRepositoryId,
        "portal_deployment_id": status.portalDeploymentId,
        "github_draft_url": status.githubDraftUrl,
        "github_release_url": status.githubReleaseUrl,
        "maven_submission_generation": status.mavenSubmissionGeneration,
        "stage_attempt": status.stageAttempt,
        "stage_started_at_millis": status.stageStartedAtMillis,
        "next_retry_at_millis": status.nextRetryAtMillis,
        "maven_started": str(any(x.submissionStarted for x in status.mavenGenerations)).lower(),
        "maven_complete": str(bool(status.mavenCentralUrl)).lower(),
        "ownership_owner": status.ownership.owner if status.ownership else "",
        "manual_maven_state": status.ownership.manualMavenState if status.ownership else "",
        "maven_payload_recorded": str(status.mavenPayload is not None).lower(),
    }
    for name, value in values.items():
        output(name, value)


async def record_maven_payload(
    client: Client, tag: str, commit: str, artifact: GithubArtifactReceipt
) -> None:
    execution, status = await find_release(client, tag, commit)

    async def update() -> ReleaseStatus:
        return cast(
            ReleaseStatus,
            await client.get_workflow_handle(execution.id, run_id=execution.run_id).execute_update(
                "recordMavenPayload", artifact, result_type=ReleaseStatus
            ),
        )

    identity = status.identity
    assert identity is not None
    updated = await with_release_worker(client, execution, identity, update)
    assert updated.identity is not None
    write_identity(execution, updated.identity, updated.phase)
    write_status(updated)


def verify_approver(actor: str) -> None:
    status = subprocess.run(
        ["bash", str(ROOT / ".github/scripts/temporal-release/verify-approver.sh"), actor], cwd=ROOT
    ).returncode
    if status == 43:
        raise ValueError("GitHub actor is not a temporalio/sdk team member.")
    if status:
        raise RuntimeError("GitHub temporalio/sdk team membership is temporarily unavailable.")


async def claim_manual(
    client: Client, env: Mapping[str, str], tag: str, commit: str, digest: str, confirmed: bool
) -> None:
    actor = required(env, "GITHUB_TRIGGERING_ACTOR")
    verify_approver(actor)
    claim = OwnershipClaim(
        tag, commit, digest or None, "MANUAL", actor, int(required(env, "GITHUB_RUN_ID")), confirmed
    )
    worker = Worker(client, task_queue=ownership_queue(tag), workflows=[ReleaseOwnershipWorkflow])
    task = asyncio.create_task(worker.run())
    try:
        status = await claim_ownership(client, claim)
    finally:
        await worker.shutdown()
        await task
    if status.owner != "MANUAL":
        raise RuntimeError("The automatic release still owns this tag; complete its handoff first.")
    output("owner", status.owner)
    output("release_digest", status.releaseDigest)
    output("manual_maven_state", status.manualMavenState)


async def manual_maven(
    client: Client, env: Mapping[str, str], state: str, tag: str, commit: str, digest: str
) -> None:
    actor = required(env, "GITHUB_TRIGGERING_ACTOR")
    verify_approver(actor)
    attempt = ManualMavenAttempt(
        state, tag, commit, digest, actor, int(required(env, "GITHUB_RUN_ID"))
    )
    worker = Worker(client, task_queue=ownership_queue(tag), workflows=[ReleaseOwnershipWorkflow])
    task = asyncio.create_task(worker.run())
    try:
        status = await ownership_handle(client, tag).execute_update(
            "recordManualMaven", attempt, result_type=OwnershipStatus
        )
    finally:
        await worker.shutdown()
        await task
    output("manual_maven_state", status.manualMavenState)


def allowed_commits(env: Mapping[str, str]) -> str:
    value = (
        required(env, "RELEASE_AUTOMATION_REF")
        + ","
        + env.get("RELEASE_AUTOMATION_COMPATIBLE_REFS", "")
    )
    if any(item and not re.fullmatch(r"[0-9a-f]{40}", item) for item in value.split(",")):
        raise ValueError("Trusted release-automation refs must be full commit SHAs.")
    return value


def require_trusted(actual: str, allowed: str) -> None:
    if actual not in allowed.split(","):
        raise RuntimeError(
            "Release identity selects an automation commit outside the protected allowlist."
        )


async def discover(client: Client, scope: str, env: Mapping[str, str]) -> None:
    allowed = allowed_commits(env)
    jobs: list[DiscoveryJob] = []
    executions = await list_workflows(client, "ReleaseWorkflow")
    if scope == "unprivileged":
        for execution in await list_workflows(client, "CandidateWorkflow"):
            try:
                jobs.extend(await discover_candidate(execution, allowed))
            except (RuntimeError, ValueError) as error:
                print(
                    f"Skipping malformed release execution {execution.id}: {error}", file=sys.stderr
                )
        for execution in executions:
            try:
                job = await discover_release(execution, allowed)
                if job:
                    jobs.append(job)
            except (RuntimeError, ValueError) as error:
                print(
                    f"Skipping malformed release execution {execution.id}: {error}", file=sys.stderr
                )
    elif scope == "publication":
        for execution in executions:
            try:
                job = await discover_publication(execution, allowed)
                if job:
                    jobs.append(job)
            except (RuntimeError, ValueError) as error:
                print(
                    f"Skipping malformed release execution {execution.id}: {error}", file=sys.stderr
                )
    elif scope == "approvals":
        for execution in executions:
            try:
                job = await discover_approval(execution, allowed)
                if job:
                    jobs.append(job)
            except (RuntimeError, ValueError) as error:
                print(
                    f"Skipping malformed release execution {execution.id}: {error}", file=sys.stderr
                )
    else:
        raise ValueError("Discovery scope must be unprivileged, publication, or approvals.")
    output("matrix", json.dumps({"include": json_value(jobs)}, separators=(",", ":")))
    output("count", len(jobs))


async def discover_candidate(execution: WorkflowExecution, allowed: str) -> list[DiscoveryJob]:
    prefix = "sdk-java-release-candidate/"
    if not execution.id.startswith(prefix):
        raise RuntimeError("Unexpected sdk-java candidate Workflow ID.")
    digest = execution.id[len(prefix) :]
    candidate = await memo(execution, "CandidateIdentity", CandidateIdentity)
    if candidate is None or candidate.digest() != digest:
        raise RuntimeError("Candidate memo does not match its Workflow ID.")
    require_trusted(candidate.trustedAutomationCommit, allowed)
    worker = DiscoveryJob(
        "candidate",
        candidate_queue_from_digest(digest),
        workflowId=execution.id,
        runId=execution.run_id,
        candidateDigest=digest,
        automationCommit=candidate.trustedAutomationCommit,
    )
    status = await memo(execution, "CandidateStatus", CandidateStatus)
    jobs = [worker]
    for platform in status.pendingPlatforms if status else [x.id for x in PLATFORMS]:
        spec = platform_spec(platform)
        jobs.append(
            DiscoveryJob(
                "build",
                build_queue_from_digest(digest, platform),
                runner=spec.runner,
                distribution=spec.distribution or "temurin",
                workflowId=execution.id,
                runId=execution.run_id,
                tag=candidate.tag,
                version=candidate.version,
                commitSha=candidate.commitSha,
                notesSha256=candidate.releaseNotesSha256,
                candidateDigest=digest,
                automationCommit=candidate.trustedAutomationCommit,
                platform=platform,
                javaVersion=spec.javaVersion or "17",
                assetPlatform=spec.assetPlatform,
                archiveExtension=spec.archiveExtension,
                binaryName=spec.binaryName,
            )
        )
    return jobs


async def release_identity(
    execution: WorkflowExecution, status: ReleaseStatus | None
) -> ReleaseIdentity:
    identity = (
        status.identity if status else await memo(execution, "ReleaseIdentity", ReleaseIdentity)
    )
    if identity is None:
        raise RuntimeError("Release Workflow has no immutable identity memo.")
    identity.validate()
    validate_release_parent(execution, identity)
    if execution.id != release_workflow_id(identity) or execution.task_queue != release_queue(
        identity
    ):
        raise RuntimeError("Release identity memo does not match Workflow routing.")
    return identity


async def discover_release(execution: WorkflowExecution, allowed: str) -> DiscoveryJob | None:
    status = await status_for(execution)
    identity = await release_identity(execution, status)
    require_trusted(identity.candidate.trustedAutomationCommit, allowed)
    if status and status.phase in {"PAUSED", "BLOCKED", "HANDED_OFF"}:
        return None
    return DiscoveryJob(
        "release",
        execution.task_queue,
        workflowId=execution.id,
        runId=execution.run_id,
        tag=identity.candidate.tag,
        commitSha=identity.candidate.commitSha,
        automationCommit=identity.candidate.trustedAutomationCommit,
    )


async def discover_publication(execution: WorkflowExecution, allowed: str) -> DiscoveryJob | None:
    status = await status_for(execution)
    phases = {
        "PREFLIGHT",
        "AWAITING_MAVEN_PAYLOAD",
        "MAVEN_REPOSITORY",
        "MAVEN_PORTAL",
        "MAVEN_PUBLISH",
        "GITHUB_DRAFT",
        "PUBLISH_GITHUB",
    }
    if (
        status is None
        or status.identity is None
        or status.approval is None
        or status.phase not in phases
        or status.nextRetryAtMillis > int(time.time() * 1000)
    ):
        return None
    identity = await release_identity(execution, status)
    require_trusted(identity.candidate.trustedAutomationCommit, allowed)
    if execution.run_id != status.approval.runId:
        raise RuntimeError("Approved release memo does not match its execution.")
    return DiscoveryJob(
        "publication",
        publication_queue(identity, status.mavenSubmissionGeneration),
        workflowId=execution.id,
        runId=execution.run_id,
        tag=identity.candidate.tag,
        commitSha=identity.candidate.commitSha,
        releaseDigest=identity.digest(),
        automationCommit=identity.candidate.trustedAutomationCommit,
    )


async def discover_approval(execution: WorkflowExecution, allowed: str) -> DiscoveryJob | None:
    status = await status_for(execution)
    if (
        status is None
        or status.identity is None
        or status.phase != "AWAITING_APPROVAL"
        or status.approval is not None
    ):
        return None
    identity = await release_identity(execution, status)
    require_trusted(identity.candidate.trustedAutomationCommit, allowed)
    request = status.approvalRequest
    return DiscoveryJob(
        "approval" if request is None else "approval-recovery",
        execution.task_queue,
        workflowId=execution.id,
        runId=execution.run_id,
        tag=identity.candidate.tag,
        commitSha=identity.candidate.commitSha,
        notesSha256=identity.candidate.releaseNotesSha256,
        manifestSha256=identity.manifestSha256,
        releaseDigest=identity.digest(),
        candidateRunId=identity.candidateRunId,
        automationCommit=identity.candidate.trustedAutomationCommit,
        approvalIssueNumber=str(request.githubIssueNumber) if request else None,
        approvalIssueNodeId=request.githubIssueNodeId if request else None,
        approvalIssueBodySha256=request.githubIssueBodySha256 if request else None,
    )


async def approval_issue(client: Client, issue: int) -> tuple[WorkflowExecution, ReleaseStatus]:
    matches = []
    for execution in await list_workflows(client, "ReleaseWorkflow"):
        status = await valid_release(execution)
        if (
            status
            and status.phase == "AWAITING_APPROVAL"
            and status.approvalRequest
            and status.approvalRequest.githubIssueNumber == issue
        ):
            matches.append((execution, status))
    if len(matches) != 1:
        raise RuntimeError("The Actions run is not bound to exactly one pending release.")
    return matches[0]


def require_checked_out(identity: ReleaseIdentity, env: Mapping[str, str]) -> None:
    if identity.candidate.trustedAutomationCommit != required(env, "RELEASE_AUTOMATION_REF"):
        raise RuntimeError("Actions did not check out this release's trusted Worker commit.")


async def request_approval(client: Client, env: Mapping[str, str]) -> None:
    expected = required(env, "EXPECTED_WORKFLOW_ID")
    matches = [
        (x, status)
        for x in await list_workflows(client, "ReleaseWorkflow")
        if (status := await valid_release(x)) and x.id == expected
    ]
    if len(matches) != 1:
        raise RuntimeError(
            f"Approval request does not identify exactly one open release; found {len(matches)}."
        )
    execution, status = matches[0]
    identity = status.identity
    assert identity
    request = ApprovalRequest(
        identity.digest(),
        execution.id,
        execution.run_id,
        int(required(env, "GITHUB_RUN_ID")),
        int(required(env, "APPROVAL_ISSUE_NUMBER")),
        required(env, "APPROVAL_ISSUE_NODE_ID"),
        required(env, "APPROVAL_ISSUE_BODY_SHA256"),
        required(env, "APPROVAL_ISSUE_CREATOR"),
        identity.candidate.trustedAutomationCommit,
    )

    async def update() -> ReleaseStatus:
        require_checked_out(identity, env)
        current = cast(
            ReleaseStatus,
            await client.get_workflow_handle(execution.id, run_id=execution.run_id).query(
                "status", result_type=ReleaseStatus
            ),
        )
        if current.approvalRequest is None:
            return cast(
                ReleaseStatus,
                await client.get_workflow_handle(
                    execution.id, run_id=execution.run_id
                ).execute_update("requestApproval", request, result_type=ReleaseStatus),
            )
        if not current.approvalRequest.same_issue(request):
            raise RuntimeError("The release already has a different immutable approval issue.")
        return current

    await with_release_worker(client, execution, identity, update)
    write_identity(execution, identity, status.phase)


async def approve(client: Client, env: Mapping[str, str]) -> None:
    actor = required(env, "GITHUB_TRIGGERING_ACTOR")
    verify_approver(actor)
    if required(env, "GITHUB_EVENT_NAME") not in {"issues", "schedule"}:
        raise RuntimeError(
            "Approval must be delivered by the issue event or its scheduled recovery."
        )
    issue = int(required(env, "APPROVAL_ISSUE_NUMBER"))
    execution, status = await approval_issue(client, issue)
    identity = status.identity
    assert identity
    require_checked_out(identity, env)
    evidence = ApprovalEvidence(
        identity.digest(),
        execution.id,
        execution.run_id,
        int(required(env, "GITHUB_RUN_ID")),
        actor,
        issue,
        required(env, "APPROVAL_ISSUE_NODE_ID"),
        required(env, "APPROVAL_ISSUE_BODY_SHA256"),
        identity.candidate.trustedAutomationCommit,
    )

    async def update() -> ReleaseStatus:
        return cast(
            ReleaseStatus,
            await client.get_workflow_handle(execution.id, run_id=execution.run_id).execute_update(
                "approve", evidence, result_type=ReleaseStatus
            ),
        )

    await with_release_worker(client, execution, identity, update)
    write_identity(execution, identity, "APPROVED")


async def control(
    client: Client, env: Mapping[str, str], action: str, tag: str, commit: str
) -> None:
    actor = env.get("CONTROL_GITHUB_ACTOR") or required(env, "GITHUB_TRIGGERING_ACTOR")
    verify_approver(actor)
    run_id = int(env.get("CONTROL_GITHUB_RUN_ID") or required(env, "GITHUB_RUN_ID"))
    execution, status = await find_release(client, tag, commit)
    identity = status.identity
    assert identity
    reasons = {
        "pause": "Release manager paused Temporal publication.",
        "resume": "Release manager resumed Temporal publication.",
        "handoff-manual": "Release manager transferred ownership to the existing manual workflow.",
        "retry-maven-submission": "Release manager inspected Sonatype and authorized one new staging generation.",
    }
    if action not in reasons:
        raise ValueError("Unknown release control action.")
    evidence = ControlEvidence(
        action,
        identity.digest(),
        execution.id,
        execution.run_id,
        run_id,
        actor,
        tag,
        commit,
        reasons[action],
    )
    if action == "handoff-manual":
        requested = env.get("MANUAL_MAVEN_REQUESTED", "false")
        if requested not in {"true", "false"}:
            raise ValueError("MANUAL_MAVEN_REQUESTED must be true or false.")
        evidence.manualMavenRequested = requested == "true"
    elif action == "retry-maven-submission":
        evidence.mavenSubmissionGeneration = int(required(env, "MAVEN_RETRY_GENERATION"))
        evidence.mavenInspection = read(
            required(env, "MAVEN_RETRY_INSPECTION_FILE"), MavenInspection
        )
        evidence.authorizationSha256 = sha256(evidence.mavenInspection.canonical(identity.digest()))
    evidence.validate()
    require_checked_out(identity, env)

    async def update() -> ReleaseStatus:
        return cast(
            ReleaseStatus,
            await client.get_workflow_handle(execution.id, run_id=execution.run_id).execute_update(
                "control", evidence, result_type=ReleaseStatus
            ),
        )

    updated = await with_release_worker(client, execution, identity, update)
    write_identity(execution, identity, updated.phase)
    write_status(updated)
    if action == "handoff-manual":
        path = Path(required(env, "RUNNER_TEMP")) / "sdk-java-release-handoff.json"
        path.write_text(json.dumps(json_value(updated), separators=(",", ":")))
        output("handoff_file", path)


async def inspect(client: Client, tag: str, commit: str, optional: bool = False) -> None:
    if optional:
        matches = []
        for execution in await list_workflows(client, "ReleaseWorkflow", False):
            status = await valid_release(execution)
            if (
                status
                and status.identity
                and (status.identity.candidate.tag, status.identity.candidate.commitSha)
                == (tag, commit)
            ):
                matches.append((execution, status))
        if not matches:
            output("found", "false")
            output("phase", "NO_WORKFLOW")
            return
        if len(matches) != 1:
            raise RuntimeError("Tag and SHA identify multiple release executions.")
        execution, status = matches[0]
        output("found", "true")
    else:
        execution, status = await find_release_prefer_open(client, tag, commit)
    assert status.identity
    write_identity(execution, status.identity, status.phase)
    write_status(status)


async def publication_input(client: Client, tag: str, commit: str, path: Path) -> None:
    execution, status = await find_release(client, tag, commit)
    if status.identity is None or status.approval is None or status.approvalRequest is None:
        raise RuntimeError("The exact release has no approved publication input.")
    value = PublicationInput(
        status.identity,
        status.approvalRequest,
        status.approval,
        execution.id,
        execution.run_id,
        status.mavenSubmissionGeneration,
        status.mavenRetryAuthorization,
        status.mavenPayload,
        list(status.mavenGenerations),
    )
    path.write_text(json.dumps(json_value(value), separators=(",", ":")))
    write_identity(execution, status.identity, status.phase)
    write_status(status)
    for name, item in (
        ("approval_run_id", status.approval.githubApprovalRunId),
        ("approval_actor", status.approval.githubActor),
        ("approval_issue_number", status.approval.githubIssueNumber),
        ("approval_issue_node_id", status.approval.githubIssueNodeId),
        ("approval_issue_body_sha256", status.approval.githubIssueBodySha256),
        ("maven_submission_generation", status.mavenSubmissionGeneration),
        (
            "maven_retry_authorization_sha256",
            status.mavenRetryAuthorization.authorizationSha256
            if status.mavenRetryAuthorization
            else "",
        ),
        ("publication_input_file", path),
    ):
        output(name, item)


async def run_worker(client: Client, role: str, queue: str, env: Mapping[str, str]) -> None:
    if not queue.startswith("sdk-java-release-"):
        raise ValueError("Refusing to poll a non-release Task Queue.")
    workers: list[Worker] = []
    started, completed = asyncio.Event(), asyncio.get_running_loop().create_future()
    if role == "candidate":
        workers.append(Worker(client, task_queue=queue, workflows=[CandidateWorkflow]))
    elif role == "release":
        ownership = OwnershipActivities(client)
        workers.extend(
            (
                Worker(client, task_queue=queue, workflows=[ReleaseWorkflow]),
                Worker(
                    client,
                    task_queue=ownership_queue(required(env, "RELEASE_TAG")),
                    workflows=[ReleaseOwnershipWorkflow],
                    activities=[ownership.claim_temporal, ownership.handoff_manual],
                ),
            )
        )
    elif role == "publication":
        source = Path(required(env, "RELEASE_SOURCE_DIR")).absolute()
        if not source.is_dir():
            raise ValueError("RELEASE_SOURCE_DIR is not a directory.")
        activities = PublicationActivities(ROOT, source, client, env, started, completed)
        workers.extend(
            (
                Worker(
                    client,
                    task_queue=queue,
                    activities=[
                        activities.preflight,
                        activities.reconcile_maven_repository,
                        activities.reconcile_maven_portal,
                        activities.publish_maven,
                        activities.reconcile_github_draft,
                        activities.publish_github_release,
                    ],
                ),
                Worker(
                    client,
                    task_queue=ownership_queue(required(env, "RELEASE_TAG")),
                    workflows=[ReleaseOwnershipWorkflow],
                ),
            )
        )
    else:
        raise ValueError(f"Unknown Worker role: {role}")
    tasks = [asyncio.create_task(worker.run()) for worker in workers]
    failure: BaseException | None = None
    try:
        processed = False
        if role == "publication":
            try:
                await asyncio.wait_for(started.wait(), 120)
            except TimeoutError:
                pass
            else:
                try:
                    failure = await asyncio.wait_for(asyncio.shield(completed), 98 * 60)
                    processed = True
                except TimeoutError:
                    failure = None
        else:
            await asyncio.sleep(10 * 60)
            await fail_on_workflow_failure(client, env)
            failure = None
        output(
            "worker_outcome", "activity-attempt-finished" if processed else "capacity-window-ended"
        )
    finally:
        await asyncio.gather(*(worker.shutdown() for worker in workers))
        await asyncio.gather(*tasks)
    if role == "publication" and failure:
        if isinstance(failure, ApplicationError) and failure.non_retryable:
            raise RuntimeError(
                "The release Activity reached a durable non-retryable failure."
            ) from failure
        output("worker_outcome", "activity-attempt-failed-temporal-will-retry")
        raise RuntimeError(
            "The release Activity attempt failed; Temporal retained its durable retry state and scheduled recovery."
        ) from failure


async def fail_on_workflow_failure(client: Client, env: Mapping[str, str]) -> None:
    handle = client.get_workflow_handle(
        required(env, "EXPECTED_WORKFLOW_ID"), run_id=env.get("EXPECTED_RUN_ID")
    )
    failure = unrecovered_workflow_failure((await handle.fetch_history()).events)
    if failure:
        output("worker_outcome", failure)
        raise RuntimeError(f"The release Workflow failed: {failure}.")


def unrecovered_workflow_failure(events: Any) -> str | None:
    completed = failed = -1
    terminal = None
    for event in events:
        name = event.EventType.Name(event.event_type)
        if name == "EVENT_TYPE_WORKFLOW_TASK_COMPLETED":
            completed = event.event_id
        elif name in {"EVENT_TYPE_WORKFLOW_TASK_FAILED", "EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT"}:
            failed = event.event_id
        elif name in {
            "EVENT_TYPE_WORKFLOW_EXECUTION_FAILED",
            "EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT",
            "EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED",
            "EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED",
        }:
            terminal = name.removeprefix("EVENT_TYPE_").lower().replace("_", "-")
    return terminal or ("workflow-task-failed-or-timed-out" if failed > completed else None)


async def async_main(argv: list[str], env: Mapping[str, str]) -> None:
    if not argv:
        raise ValueError("Expected a release automation command.")
    command, args = argv[0], argv[1:]
    local = {"candidate-outputs": 1, "maven-policy": 1, "platform-matrix": 0}
    if command in local:
        if len(args) != local[command]:
            raise ValueError("Unexpected command arguments.")
        if command == "candidate-outputs":
            candidate_outputs(read(args[0], CandidateIdentity))
        elif command == "maven-policy":
            maven_policy(Path(args[0]))
        else:
            output(
                "matrix",
                json.dumps(
                    {"include": [item.matrix() for item in PLATFORMS]}, separators=(",", ":")
                ),
            )
        return
    expected = {
        "start-candidate": 1,
        "record-artifact": 4,
        "record-maven-payload": 3,
        "claim-manual-ownership": 4,
        "start-manual-maven": 3,
        "complete-manual-maven": 3,
        "discover": 1,
        "approval-target": 0,
        "approval-request": 0,
        "approve": 0,
        "control": 3,
        "inspect": 2,
        "inspect-if-present": 2,
        "publication-input": 3,
        "worker": 2,
    }
    if command not in expected:
        raise ValueError(f"Unknown command: {command}")
    if len(args) != expected[command]:
        raise ValueError("Unexpected command arguments.")
    client = await connect(env)
    if command == "start-candidate":
        await start_candidate(client, read(args[0], CandidateIdentity))
    elif command == "record-artifact":
        await record_artifact(
            client, args[0], args[1], args[2], read(args[3], GithubArtifactReceipt)
        )
    elif command == "record-maven-payload":
        await record_maven_payload(client, args[0], args[1], read(args[2], GithubArtifactReceipt))
    elif command == "claim-manual-ownership":
        await claim_manual(
            client,
            env,
            args[0],
            args[1],
            "" if args[2] == "-" else args[2],
            args[3].lower() == "true",
        )
    elif command in {"start-manual-maven", "complete-manual-maven"}:
        await manual_maven(
            client, env, "STARTED" if command.startswith("start") else "COMPLETED", *args
        )
    elif command == "discover":
        await discover(client, args[0], env)
    elif command == "approval-request":
        await request_approval(client, env)
    elif command == "approve":
        await approve(client, env)
    elif command == "approval-target":
        execution, status = await approval_issue(
            client, int(required(env, "APPROVAL_ISSUE_NUMBER"))
        )
        assert status.identity
        write_identity(execution, status.identity, status.phase)
    elif command == "control":
        await control(client, env, *args)
    elif command == "inspect":
        await inspect(client, args[0], args[1])
    elif command == "inspect-if-present":
        await inspect(client, args[0], args[1], True)
    elif command == "publication-input":
        await publication_input(client, args[0], args[1], Path(args[2]))
    elif command == "worker":
        await run_worker(client, args[0], args[1], env)


def main() -> None:
    if not (ROOT / ".github/scripts/temporal-release/verify-approver.sh").is_file():
        raise RuntimeError("The trusted repository root has an unexpected layout.")
    asyncio.run(async_main(sys.argv[1:], os.environ))


if __name__ == "__main__":
    main()
