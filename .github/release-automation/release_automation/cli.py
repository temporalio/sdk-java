import asyncio
import json
import os
import re
import subprocess
import sys
from collections.abc import Mapping
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
    text = str(value).replace("\n", " ") if value is not None else ""
    if destination := os.environ.get("GITHUB_OUTPUT"):
        with Path(destination).open("a") as stream:
            stream.write(f"{name}={text}\n")
    else:
        print(f"{name}={text}")


def required(env: Mapping[str, str], name: str) -> str:
    if not (value := env.get(name)):
        raise ValueError(f"Required Actions value is missing: {name}")
    return value


def read(path: str | Path, kind: type[T]) -> T:
    return cast(T, value_to_type(kind, json.loads(Path(path).read_text())))


async def connect(env: Mapping[str, str]) -> Client:
    return await Client.connect(
        required(env, "TEMPORAL_ADDRESS"),
        namespace=required(env, "TEMPORAL_NAMESPACE"),
        api_key=required(env, "TEMPORAL_API_KEY"),
        tls=True,
    )


def policy_for_settings(settings: Path) -> str:
    projects = [
        match.group(1)
        for line in settings.read_text().splitlines()
        if (match := re.fullmatch(r"include ['\"]([^'\"]+)['\"]", line))
    ]
    return maven_policy_for_projects(projects)


def git(*args: str, cwd: Path | None = None) -> str:
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()


def candidate_from_push(env: Mapping[str, str]) -> CandidateIdentity:
    if env.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise ValueError("This automation only releases temporalio/sdk-java.")
    commit, automation = required(env, "RELEASE_COMMIT"), required(env, "RELEASE_AUTOMATION_REF")
    base = required(env, "BASE_SHA")
    if re.fullmatch(r"0+", base):
        base = git("rev-parse", "HEAD^")
    if (
        git("rev-parse", "HEAD^{commit}") != commit
        or git("rev-parse", "HEAD^{commit}", cwd=ROOT) != automation
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
        automation,
        policy_for_settings(Path("settings.gradle")),
        int(required(env, "GITHUB_RUN_ID")),
    )


async def workflows(client: Client) -> list[WorkflowExecution]:
    query = "ExecutionStatus = 'Running' AND WorkflowType = 'ReleaseWorkflow'"
    return [item async for item in client.list_workflows(query)]


async def memo(execution: WorkflowExecution, key: str, kind: type[T]) -> T | None:
    return await execution.memo_value(key, None, type_hint=kind)


async def start_candidate(client: Client, candidate: CandidateIdentity) -> None:
    candidate.validate()
    for execution in await workflows(client):
        existing = await memo(execution, "CandidateIdentity", CandidateIdentity)
        if existing and existing.tag == candidate.tag and existing.digest() != candidate.digest():
            raise RuntimeError("The release tag already identifies another immutable candidate.")
    await client.start_workflow(
        "ReleaseWorkflow",
        candidate,
        id=candidate_workflow_id(candidate),
        task_queue=candidate_queue(candidate),
        id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
        id_conflict_policy=WorkflowIDConflictPolicy.USE_EXISTING,
        memo={"CandidateIdentity": candidate},
    )


async def update(
    client: Client,
    workflow_id: str,
    run_id: str,
    name: str,
    *args: Any,
) -> None:
    if not run_id:
        raise ValueError("Release Workflow execution is invalid.")
    async with Worker(client, task_queue=workflow_queue(workflow_id), workflows=[ReleaseWorkflow]):
        await client.get_workflow_handle(workflow_id, run_id=run_id).execute_update(
            name, args=args, result_type=ReleaseStatus
        )


def workflow_queue(workflow_id: str) -> str:
    prefix = "sdk-java-release-candidate/"
    if not workflow_id.startswith(prefix):
        raise ValueError("Release Workflow ID is invalid.")
    return candidate_queue_from_digest(workflow_id.removeprefix(prefix))


def verify_candidate_origin(candidate: CandidateIdentity, run: Mapping[str, Any]) -> None:
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
    return cast(
        Mapping[str, Any],
        json.loads(
            subprocess.check_output(
                ["gh", "api", f"repos/{REPOSITORY}/actions/runs/{run_id}"], text=True
            )
        ),
    )


async def release_jobs(
    execution: WorkflowExecution, automation: str
) -> dict[str, list[dict[str, Any]]]:
    candidate = await memo(execution, "CandidateIdentity", CandidateIdentity)
    if (
        candidate is None
        or execution.id != candidate_workflow_id(candidate)
        or execution.task_queue != candidate_queue(candidate)
        or candidate.trustedAutomationCommit != automation
    ):
        raise RuntimeError("Release execution does not match its immutable candidate routing.")
    verify_candidate_origin(candidate, github_run(candidate.githubRunId))
    status = await memo(execution, "ReleaseStatus", ReleaseStatus)
    common = {
        "workflowId": execution.id,
        "runId": execution.run_id,
        "tag": candidate.tag,
        "commitSha": candidate.commitSha,
        "automationCommit": candidate.trustedAutomationCommit,
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


async def discover(client: Client, env: Mapping[str, str]) -> None:
    jobs: dict[str, list[dict[str, Any]]] = {"build": [], "publication": []}
    automation = required(env, "RELEASE_AUTOMATION_REF")
    for execution in await workflows(client):
        try:
            found = await release_jobs(execution, automation)
            for name in jobs:
                jobs[name].extend(found[name])
        except (RuntimeError, ValueError) as error:
            print(f"Skipping malformed release {execution.id}: {error}", file=sys.stderr)
    for name, selected in jobs.items():
        output(f"{name}_count", len(selected))
        output(
            f"{name}_matrix",
            json.dumps({"include": json_value(selected)}, separators=(",", ":")),
        )


async def run_worker(client: Client, queue: str, env: Mapping[str, str]) -> None:
    if not queue.startswith("sdk-java-release-") or "-publication-g" not in queue:
        raise ValueError("Refusing to poll a non-publication Task Queue.")
    workflow_id = required(env, "EXPECTED_WORKFLOW_ID")
    source = Path(required(env, "RELEASE_SOURCE_DIR")).absolute()
    if not source.is_dir():
        raise ValueError("RELEASE_SOURCE_DIR is not a directory.")
    activities = PublicationActivities(ROOT, source, env)
    release_worker = Worker(
        client, task_queue=workflow_queue(workflow_id), workflows=[ReleaseWorkflow]
    )
    publication_worker = Worker(
        client,
        task_queue=queue,
        activities=[activities.publish_release, activities.inspect_maven],
    )
    handle = client.get_workflow_handle(workflow_id, run_id=env.get("EXPECTED_RUN_ID"))
    async with release_worker, publication_worker:
        try:
            await asyncio.wait_for(asyncio.shield(handle.result()), 95 * 60)
        except TimeoutError:
            pass


async def async_main(argv: list[str], env: Mapping[str, str]) -> None:
    client = await connect(env)
    match argv:
        case ["start"]:
            await start_candidate(client, candidate_from_push(env))
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
        case ["discover"]:
            await discover(client, env)
        case ["worker", queue]:
            await run_worker(client, queue, env)
        case _:
            raise ValueError("Unknown command or unexpected arguments.")


def main() -> None:
    if not (ROOT / ".github/scripts/temporal-release/reconcile-publication.sh").is_file():
        raise RuntimeError("The trusted repository root has an unexpected layout.")
    asyncio.run(async_main(sys.argv[1:], os.environ))


if __name__ == "__main__":
    main()
