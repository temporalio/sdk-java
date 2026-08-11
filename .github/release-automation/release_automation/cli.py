import asyncio
import json
import os
import re
import subprocess
import sys
from collections.abc import Mapping
from contextlib import AsyncExitStack
from pathlib import Path
from typing import Any, TypeVar, cast

from temporalio.client import Client, WorkflowExecution
from temporalio.common import WorkflowIDConflictPolicy, WorkflowIDReusePolicy
from temporalio.converter import value_to_type
from temporalio.worker import Worker

from .activities import PublicationActivities
from .models import (
    PLATFORMS,
    REPOSITORY,
    TAG,
    CandidateIdentity,
    GithubArtifactReceipt,
    ReleaseStatus,
    candidate_queue,
    candidate_queue_from_digest,
    candidate_workflow_id,
    json_value,
    maven_artifacts,
    maven_policy_for_projects,
    native_artifact_name,
    platform_spec,
    publication_queue,
)
from .workflows import ReleaseWorkflow

T = TypeVar("T")
ROOT = Path(__file__).resolve().parents[3]
RELEASE_BRANCH = re.compile(r"(?:main|releases/.+|[^/]*\.[^/]*\.x|release_[^/]*_[^/]*_x)")


def output(name: str, value: Any) -> None:
    """Write an Actions output, falling back to readable local command output."""
    text = str(value).replace("\n", " ") if value is not None else ""
    if destination := os.environ.get("GITHUB_OUTPUT"):
        with Path(destination).open("a") as stream:
            stream.write(f"{name}={text}\n")
    else:
        print(f"{name}={text}")


def required(env: Mapping[str, str], name: str) -> str:
    """Read a required Actions value without silently accepting an empty secret."""
    if not (value := env.get(name)):
        raise ValueError(f"Required Actions value is missing: {name}")
    return value


def read(path: str | Path, kind: type[T]) -> T:
    """Decode JSON through Temporal's converter so CLI and Workflow types agree."""
    return cast(T, value_to_type(kind, json.loads(Path(path).read_text())))


async def connect(env: Mapping[str, str]) -> Client:
    """Connect to the configured Temporal namespace using its single API credential."""
    return await Client.connect(
        required(env, "TEMPORAL_ADDRESS"),
        namespace=required(env, "TEMPORAL_NAMESPACE"),
        api_key=required(env, "TEMPORAL_API_KEY"),
        tls=True,
    )


def policy_for_settings(settings: Path) -> str:
    """Recognize the checked-out Gradle project set as a fixed Maven policy."""
    projects = [
        match.group(1)
        for line in settings.read_text().splitlines()
        if (match := re.fullmatch(r"include ['\"]([^'\"]+)['\"]", line))
    ]
    return maven_policy_for_projects(projects)


def git(*args: str, cwd: Path | None = None) -> str:
    """Run a read-only Git command and return its whitespace-trimmed output."""
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()


def candidate_from_push(env: Mapping[str, str]) -> CandidateIdentity:
    """Authorize a candidate from one newly added release-note file.

    The push workflow is the approval boundary. This function binds that approval to
    the exact merged commit, verifies ancestry, and refuses ambiguous or
    non-regular release-note changes before any Temporal execution is started.
    """
    if env.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise ValueError("This automation only releases temporalio/sdk-java.")
    commit = required(env, "RELEASE_COMMIT")
    base = required(env, "BASE_SHA")
    if re.fullmatch(r"0+", base):
        base = git("rev-parse", "HEAD^")
    if (
        git("rev-parse", "HEAD^{commit}") != commit
        or git("rev-parse", "HEAD^{commit}", cwd=ROOT) != commit
    ):
        raise ValueError("A source or automation checkout changed.")
    git("merge-base", "--is-ancestor", base, commit)
    fields = git(
        "diff", "--name-status", "--no-renames", "-z", base, commit, "--", "releases/"
    ).split("\0")
    if (
        len(fields) != 3
        or fields[0] != "A"
        or not (match := re.fullmatch(rf"releases/({TAG})", fields[1]))
    ):
        raise ValueError("The push must add exactly one valid release-note file.")
    notes, tag = Path(fields[1]), match.group(1)
    tree = git("ls-tree", commit, "--", str(notes)).split(maxsplit=3)
    if (
        len(tree) != 4
        or tree[:2] != ["100644", "blob"]
        or not notes.is_file()
        or notes.is_symlink()
        or not notes.stat().st_size
    ):
        raise ValueError("Release notes must be a regular file.")
    return CandidateIdentity(
        tag,
        commit,
        policy_for_settings(Path("settings.gradle")),
        int(required(env, "GITHUB_RUN_ID")),
    )


async def running_releases(client: Client) -> list[WorkflowExecution]:
    """List running releases while checking that a tag has only one identity."""
    query = "ExecutionStatus = 'Running' AND WorkflowType = 'ReleaseWorkflow'"
    return [item async for item in client.list_workflows(query)]


async def memo(execution: WorkflowExecution, key: str, kind: type[T]) -> T | None:
    """Decode one typed memo field without requiring a live Workflow Worker."""
    return await execution.memo_value(key, None, type_hint=kind)


async def start_candidate(client: Client, candidate: CandidateIdentity) -> tuple[str, str]:
    """Start or reuse the one Workflow execution for an immutable candidate.

    Before starting, all running executions are checked for a conflicting use of the
    same release tag. Temporal's duplicate policies then make repeated delivery of the
    same authorized push idempotent without allowing identity replacement.
    """
    candidate.validate()
    for execution in await running_releases(client):
        existing = await memo(execution, "CandidateIdentity", CandidateIdentity)
        if existing and existing.tag == candidate.tag and existing.digest() != candidate.digest():
            raise RuntimeError("The release tag already identifies another immutable candidate.")
    handle = await client.start_workflow(
        "ReleaseWorkflow",
        candidate,
        id=candidate_workflow_id(candidate),
        task_queue=candidate_queue(candidate),
        id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
        id_conflict_policy=WorkflowIDConflictPolicy.USE_EXISTING,
        memo={"CandidateIdentity": candidate},
    )
    if not handle.result_run_id:
        raise RuntimeError("Temporal did not identify the release Workflow execution.")
    return handle.id, handle.result_run_id


async def update(
    client: Client,
    workflow_id: str,
    run_id: str,
    name: str,
    *args: Any,
) -> None:
    """Run a short-lived Worker while delivering an update to an exact execution."""
    if not run_id:
        raise ValueError("Release Workflow execution is invalid.")
    async with Worker(client, task_queue=workflow_queue(workflow_id), workflows=[ReleaseWorkflow]):
        await client.get_workflow_handle(workflow_id, run_id=run_id).execute_update(
            name, args=args, result_type=ReleaseStatus
        )


def workflow_queue(workflow_id: str) -> str:
    """Recover and validate the candidate-specific queue encoded in a Workflow ID."""
    prefix = "sdk-java-release-candidate/"
    if not workflow_id.startswith(prefix):
        raise ValueError("Release Workflow ID is invalid.")
    return candidate_queue_from_digest(workflow_id.removeprefix(prefix))


def publication_worker_queues(queue: str) -> tuple[str, ...]:
    """Return the current publication queue and its one bounded recovery successor."""
    match = re.fullmatch(r"(sdk-java-release-[0-9a-f]{32}-publication-g)([01])", queue)
    if match is None:
        raise ValueError("Refusing to poll a non-publication Task Queue.")
    return (queue, f"{match.group(1)}1") if match.group(2) == "0" else (queue,)


def verify_candidate_origin(candidate: CandidateIdentity, run: Mapping[str, Any]) -> None:
    """Reconfirm that GitHub authorized the candidate from the expected push workflow.

    The merge-triggered run repeats this check before each privileged stage so a forged
    Temporal memo cannot cause privileged work. Repository, workflow path, source SHA,
    branch policy, event type, and numeric run identity must match the frozen candidate.
    """
    repository = run.get("head_repository")
    if (
        run.get("id") != candidate.githubRunId
        or run.get("event") != "push"
        or run.get("path") != ".github/workflows/temporal-release-candidate.yml"
        or run.get("head_sha") != candidate.commitSha
        or not RELEASE_BRANCH.fullmatch(str(run.get("head_branch", "")))
        or not isinstance(repository, Mapping)
        or repository.get("full_name") != REPOSITORY
    ):
        raise RuntimeError("GitHub run does not authorize this release candidate.")


def github_run(run_id: int) -> Mapping[str, Any]:
    """Fetch the GitHub Actions run used as candidate authorization evidence."""
    return cast(
        Mapping[str, Any],
        json.loads(
            subprocess.check_output(
                ["gh", "api", f"repos/{REPOSITORY}/actions/runs/{run_id}"], text=True
            )
        ),
    )


async def release_jobs(
    client: Client, workflow_id: str, run_id: str
) -> dict[str, list[dict[str, Any]]]:
    """Translate one durable release snapshot into minimal Actions matrix entries.

    Native entries contain no credentials and are emitted only for missing platforms.
    A publication entry is emitted only after the immutable release identity exists;
    its queue is derived from the current durable Maven generation.
    """
    description = await client.get_workflow_handle(workflow_id, run_id=run_id).describe()
    info = description.raw_description.workflow_execution_info
    candidate = await description.memo_value("CandidateIdentity", None, type_hint=CandidateIdentity)
    if (
        candidate is None
        or info.execution.workflow_id != candidate_workflow_id(candidate)
        or info.execution.run_id != run_id
        or info.task_queue != candidate_queue(candidate)
    ):
        raise RuntimeError("Release execution does not match its immutable candidate routing.")
    verify_candidate_origin(candidate, github_run(candidate.githubRunId))
    status = await description.memo_value("ReleaseStatus", None, type_hint=ReleaseStatus)
    common = {
        "workflowId": workflow_id,
        "runId": run_id,
        "tag": candidate.tag,
        "commitSha": candidate.commitSha,
    }
    jobs: dict[str, list[dict[str, Any]]] = {"build": [], "publication": []}
    if status is None or status.identity is None:
        pending = status.pendingPlatforms if status else [item.platform for item in PLATFORMS]
        digest = candidate.digest()
        for platform in pending:
            spec = platform_spec(platform)
            jobs["build"].append(
                {
                    **common,
                    "candidateDigest": digest,
                    "fileName": native_artifact_name(candidate.version, platform),
                    **spec._asdict(),
                }
            )
    if status and status.identity:
        jobs["publication"].append(
            {
                **common,
                "taskQueue": publication_queue(
                    status.identity,
                    status.mavenGenerations[-1].generation if status.mavenGenerations else 0,
                ),
                "releaseDigest": status.identity.digest(),
                "mavenPayloadRecorded": status.mavenPayload is not None,
                "mavenArtifacts": maven_artifacts(candidate.mavenPolicy),
            }
        )
    return jobs


async def output_jobs(client: Client, workflow_id: str, run_id: str) -> None:
    """Emit jobs for the exact release Workflow started by this merge run."""
    jobs = await release_jobs(client, workflow_id, run_id)
    for name, selected in jobs.items():
        output(f"{name}_count", len(selected))
        output(
            f"{name}_matrix",
            json.dumps({"include": json_value(selected)}, separators=(",", ":")),
        )


async def run_worker(client: Client, queue: str, env: Mapping[str, str]) -> None:
    """Poll one release Workflow queue and the bounded publication generation queues.

    Hosting both Workers in the publication job removes a separate Actions worker
    matrix. Activities still exist only on the privileged generation-specific queue,
    while deterministic Workflow code polls the candidate-specific queue.
    """
    queues = publication_worker_queues(queue)
    workflow_id = required(env, "EXPECTED_WORKFLOW_ID")
    source = Path(required(env, "RELEASE_SOURCE_DIR")).absolute()
    if not source.is_dir():
        raise ValueError("RELEASE_SOURCE_DIR is not a directory.")
    activities = PublicationActivities(source, env)
    release_worker = Worker(
        client, task_queue=workflow_queue(workflow_id), workflows=[ReleaseWorkflow]
    )
    handle = client.get_workflow_handle(workflow_id, run_id=env.get("EXPECTED_RUN_ID"))
    async with AsyncExitStack() as stack:
        await stack.enter_async_context(release_worker)
        for task_queue in queues:
            await stack.enter_async_context(
                Worker(
                    client,
                    task_queue=task_queue,
                    activities=[activities.publish_release, activities.inspect_maven],
                )
            )
        await handle.result()


async def async_main(argv: list[str], env: Mapping[str, str]) -> None:
    """Dispatch the small command surface used by the release workflows."""
    client = await connect(env)
    match argv:
        case ["start"]:
            workflow_id, run_id = await start_candidate(client, candidate_from_push(env))
            output("workflow_id", workflow_id)
            output("run_id", run_id)
        case ["record-artifact", workflow_id, run_id, platform, path]:
            await update(
                client,
                workflow_id,
                run_id,
                "recordArtifact",
                platform,
                read(path, GithubArtifactReceipt),
            )
        case ["record-maven", workflow_id, run_id, path]:
            await update(
                client,
                workflow_id,
                run_id,
                "recordMavenPayload",
                read(path, GithubArtifactReceipt),
            )
        case ["jobs", workflow_id, run_id]:
            await output_jobs(client, workflow_id, run_id)
        case ["worker", queue]:
            await run_worker(client, queue, env)
        case _:
            raise ValueError("Unknown command or unexpected arguments.")


def main() -> None:
    """Validate the trusted checkout layout and run the asynchronous CLI."""
    if not (ROOT / ".github/scripts/temporal-release/prepare-maven-payload.sh").is_file():
        raise RuntimeError("The trusted repository root has an unexpected layout.")
    asyncio.run(async_main(sys.argv[1:], os.environ))


if __name__ == "__main__":
    main()
