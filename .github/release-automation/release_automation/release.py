"""Durably orchestrate one SDK release after its release-note PR is merged.

GitHub Actions calls this module twice. ``start`` validates the merge and starts
the immutable Temporal Workflow. After the matrix has created the native and
Maven Actions artifacts, ``publish`` starts short-lived release-specific Workers,
signals that builds are ready, and waits for the Workflow to finish.

Publication then follows one order: freeze artifact receipts, reconcile Maven to
an exact Central state, create a GitHub draft, attach native assets, and finally
make the GitHub release public. The Workflow owns durable decisions; Activities
own all GitHub, Sonatype, Portal, and Maven Central I/O.

Artifact construction remains in ``build.py``. This module imports only its safe
unpack and validation helpers; it never invokes the build CLI during publication.
"""

import asyncio
import base64
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from collections.abc import Mapping, Sequence
from dataclasses import asdict, dataclass, field
from datetime import timedelta
from pathlib import Path
from typing import Any, TypeVar, cast

from temporalio import activity, workflow
from temporalio.client import Client
from temporalio.common import RetryPolicy, WorkflowIDConflictPolicy, WorkflowIDReusePolicy
from temporalio.exceptions import ApplicationError
from temporalio.worker import Worker

# These pure helpers are allowed through the Workflow import sandbox because the
# publication Activities use them to revalidate the frozen Maven payload.
with workflow.unsafe.imports_passed_through():
    from .build import unpack_maven, validate_maven

# Fixed repository policy. Release files choose a version, never a project set,
# platform matrix, repository, or publication endpoint.
REPOSITORY = "temporalio/sdk-java"
CENTRAL = "https://repo1.maven.org/maven2"
SONATYPE = "https://ossrh-staging-api.central.sonatype.com"
PORTAL = "https://central.sonatype.com/api/v1/publisher"
TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-RC[0-9]+)?")
SHA = re.compile(r"[0-9a-f]{40}")
PLATFORMS = {
    "linux-amd64-musl": ("linux_amd64_musl", ".tar.gz", "temporal-test-server"),
    "linux-amd64": ("linux_amd64", ".tar.gz", "temporal-test-server"),
    "macos-amd64": ("macOS_amd64", ".tar.gz", "temporal-test-server"),
    "macos-arm64": ("macOS_arm64", ".tar.gz", "temporal-test-server"),
    "linux-arm64": ("linux_arm64", ".tar.gz", "temporal-test-server"),
    "windows-amd64": ("windows_amd64", ".zip", "temporal-test-server.exe"),
}
CURRENT_MAVEN = (
    "temporal-aws-lambda temporal-bom temporal-envconfig temporal-kotlin temporal-opentelemetry "
    "temporal-opentracing temporal-remote-data-encoder temporal-sdk temporal-serviceclient "
    "temporal-shaded temporal-spring-ai temporal-spring-boot-autoconfigure "
    "temporal-spring-boot-starter temporal-test-server temporal-testing "
    "temporal-workflowcheck temporal-workflowstreams"
).split()
MODERN_ONLY = "temporal-aws-lambda temporal-envconfig temporal-opentelemetry temporal-spring-ai temporal-workflowcheck temporal-workflowstreams".split()
CLASSIC_MAVEN = [x for x in CURRENT_MAVEN if x not in MODERN_ONLY]
ALPHA_MAVEN = [x.replace("autoconfigure", "autoconfigure-alpha").replace("starter", "starter-alpha") for x in CLASSIC_MAVEN]
MAVEN_POLICIES = [CURRENT_MAVEN, CLASSIC_MAVEN, ALPHA_MAVEN, [x for x in ALPHA_MAVEN if x not in {"temporal-bom", "temporal-shaded"}]]
T = TypeVar("T")


# Durable release identity and state -----------------------------------------


def digest(value: Any) -> str:
    """Hash one JSON-compatible durable release identity."""
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


@dataclass
class Candidate:
    """Immutable release identity derived from the merged repository state."""

    tag: str
    commit: str
    maven: list[str]

    def validate(self) -> None:
        """Validate the merged identity and its fixed Maven project policy."""
        if not TAG.fullmatch(self.tag) or not SHA.fullmatch(self.commit) or self.maven not in MAVEN_POLICIES:
            raise ValueError("Invalid release identity.")

    @property
    def version(self) -> str:
        """Return the release version without its tag prefix."""
        return self.tag[1:]

    @property
    def id(self) -> str:
        """Return the stable identity shared by artifacts and Temporal routing."""
        self.validate()
        return digest([REPOSITORY, asdict(self)])


@dataclass
class Artifact:
    """Frozen GitHub Actions artifact receipt used during publication."""

    id: int
    digest: str
    file: str


@dataclass
class Generation:
    """Durable identity and observed state for one Maven submission attempt."""

    number: int
    repository: str | None = None
    repositoryState: str = ""
    portal: str | None = None
    portalState: str = ""


@dataclass
class Inspection:
    """One read-only snapshot of Maven Central and all submission generations."""

    central: int
    generations: list[Generation]


@dataclass
class ReleaseInput:
    """Complete durable state passed from the Workflow to each Activity."""

    candidate: Candidate
    artifacts: list[Artifact]
    generations: list[Generation]


@dataclass
class ReleaseResult:
    """Final publication receipt returned by a completed release Workflow."""

    digest: str
    github: str
    maven: str


@dataclass
class ReleaseStatus:
    """Compact operator-visible Workflow state exposed by the status query."""

    phase: str = "INITIALIZING"
    ready: bool = False
    artifacts: list[Artifact] = field(default_factory=list)
    generations: list[Generation] = field(default_factory=list)


def workflow_id(candidate: Candidate) -> str:
    """Route one immutable candidate to its unique Workflow execution."""
    return f"sdk-java-release/{candidate.id}"


def workflow_queue(identity: str) -> str:
    """Return the candidate-specific Workflow queue after validating its digest."""
    if not re.fullmatch(r"[0-9a-f]{64}", identity):
        raise ValueError("Invalid release queue identity.")
    return f"sdk-java-release-{identity[:32]}-workflow"


def publication_queue(identity: str, generation: int) -> str:
    """Return the release- and Maven-generation-specific Activity queue."""
    if generation not in {0, 1}:
        raise ValueError("Only bounded Maven generations 0 and 1 are supported.")
    workflow_queue(identity)
    return f"sdk-java-release-{identity[:32]}-publication-g{generation}"


def native_file(candidate: Candidate, platform: str) -> str:
    """Return one fixed public native archive filename."""
    asset, extension, _ = PLATFORMS[platform]
    return f"temporal-test-server_{candidate.version}_{asset}{extension}"


# Temporal Workflow ----------------------------------------------------------


@workflow.defn(name="ReleaseWorkflow", failure_exception_types=[Exception])
class ReleaseWorkflow:
    def __init__(self) -> None:
        """Initialize the small operator-visible durable state."""
        self.s = ReleaseStatus()
        self.candidate: Candidate | None = None

    @workflow.run
    async def run(self, candidate: Candidate) -> ReleaseResult:
        """Wait for merge-triggered builds, freeze their artifacts, and publish."""
        candidate.validate()
        self.candidate = candidate
        self.s.phase = "BUILDING"
        await workflow.wait_condition(lambda: self.s.ready)
        self.s.phase = "DISCOVERING_ARTIFACTS"

        # Discovery runs once and its IDs and digests become durable Workflow
        # state. Every retry therefore publishes the same build outputs.
        self.s.artifacts = await self._activity("discoverArtifacts", list[Artifact])
        self.s.generations = [Generation(0)]
        while True:
            self.s.phase = "PUBLISHING"
            try:
                result = await self._activity("publishRelease", ReleaseResult)
            except BaseException as error:
                if self._error_type(error) not in {
                    "MavenSubmissionAmbiguous",
                    "MavenDeploymentFailed",
                }:
                    raise
                await self._recover(error)
                continue
            self.s.phase = "PUBLISHED"
            return result

    @workflow.update(name="buildsReady")
    def builds_ready(self) -> ReleaseStatus:
        """Idempotently release the Workflow after all Actions builds succeed."""
        self.s.ready = True
        return self.s

    @workflow.query(name="status")
    def status(self) -> ReleaseStatus:
        """Return the complete compact durable release state."""
        return self.s

    async def _activity(self, name: str, result_type: type[T]) -> T:
        """Run publication work only on this release generation's queue."""
        assert self.candidate
        generation = self.s.generations[-1].number if self.s.generations else 0
        return cast(
            T,
            await workflow.execute_activity(
                name,
                ReleaseInput(self.candidate, self.s.artifacts, list(self.s.generations)),
                result_type=result_type,
                task_queue=publication_queue(self.candidate.id, generation),
                start_to_close_timeout=timedelta(minutes=90),
                retry_policy=RetryPolicy(
                    initial_interval=timedelta(minutes=2),
                    maximum_interval=timedelta(minutes=15),
                    non_retryable_error_types=[
                        "ReleaseIdentityConflict",
                        "MavenSubmissionAmbiguous",
                        "MavenDeploymentFailed",
                    ],
                ),
            ),
        )

    async def _recover(self, cause: BaseException) -> None:
        """Require two separated absence observations before one replacement.

        Sonatype may create a repository even when its response is lost. Generation zero
        is therefore inspected twice across durable time. A visible/live repository stays
        on generation zero; only repeated absence or a released failed Portal deployment
        permits generation one. Generation one can never create another successor.
        """
        assert self.candidate
        retryable = False
        for final in (False, True):
            found = await self._activity("inspectMaven", Inspection)
            self._adopt(found)
            if found.central == len(self.candidate.maven):
                return
            current = found.generations[-1]
            if current.repositoryState in {"open", "closed"} or current.portalState in {
                "PENDING",
                "VALIDATING",
                "VALIDATED",
                "PUBLISHING",
                "PUBLISHED",
            }:
                return
            retryable = not found.central and all(
                (item.repositoryState, item.portalState) in {("absent", ""), ("released", "FAILED")} for item in found.generations
            )
            if not retryable or final:
                break
            await workflow.sleep(timedelta(minutes=10))
        if not retryable or len(self.s.generations) == 2:
            raise cause
        self.s.generations.append(Generation(1))

    def _adopt(self, found: Inspection) -> None:
        """Adopt newly visible external IDs without replacing known identities."""
        if [x.number for x in found.generations] != [x.number for x in self.s.generations]:
            raise ValueError("Maven inspection does not cover durable generations.")
        for durable, observed in zip(self.s.generations, found.generations, strict=True):
            for name in ("repository", "portal"):
                old, new = getattr(durable, name), getattr(observed, name)
                if old and new and old != new:
                    raise ValueError("Maven external identity changed.")
                setattr(durable, name, old or new)

    @staticmethod
    def _error_type(error: BaseException) -> str:
        """Find a Temporal ApplicationError type through wrapper causes."""
        while error:
            if isinstance(error, ApplicationError):
                return error.type or ""
            error = cast(BaseException, error.__cause__)
        return ""


# Expected external outcomes -------------------------------------------------


class ReleaseError(RuntimeError):
    """A durable external outcome that Activity retries cannot repair."""

    error_type = "ReleaseIdentityConflict"


class MavenAmbiguous(ReleaseError):
    """A Maven mutation may have succeeded and requires Workflow inspection."""

    error_type = "MavenSubmissionAmbiguous"


class MavenFailed(ReleaseError):
    """The exact Portal deployment reached terminal validation failure."""

    error_type = "MavenDeploymentFailed"


class View:
    """Join Sonatype's staging and Portal views by repository identity."""

    def __init__(self, profiles: list[dict[str, Any]], manual: list[dict[str, Any]]) -> None:
        """Join Sonatype's staging and Portal views by repository identity."""
        self.profiles, self.manual = profiles, manual

    def find(self, description: str) -> str | None:
        """Find at most one repository carrying a generation description."""
        found = {
            cast(str, item.get("repositoryId") or item.get("id") or item.get("key"))
            for item in self.profiles + self.manual
            if item.get("description") == description and (item.get("repositoryId") or item.get("id") or item.get("key"))
        }
        if len(found) > 1:
            raise ReleaseError("Multiple repositories match one Maven generation.")
        return next(iter(found), None)

    def repository(self, identity: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        """Return both API representations of an exact repository."""
        profiles = [x for x in self.profiles if (x.get("repositoryId") or x.get("id")) == identity]
        manual = [x for x in self.manual if x.get("key") == identity]
        return profiles, manual


class Session:
    """Perform one Activity attempt against frozen artifacts and external APIs.

    A Session is disposable. Each Activity attempt receives fresh temporary
    storage, reconstructs its state from durable input and external systems, and
    cleans up afterward. It contains no durable state of its own.
    """

    def __init__(self, value: ReleaseInput, source: Path, environment: Mapping[str, str]) -> None:
        """Create one temporary reconciliation session from durable input."""
        self.value, self.candidate, self.source = value, value.candidate, source
        self.github = environment["GH_TOKEN"]
        self.user, self.password = environment["RH_USER"], environment["RH_PASSWORD"]
        self.portal_token = base64.b64encode(f"{self.user}:{self.password}".encode()).decode()
        self.temp = tempfile.TemporaryDirectory(prefix="sdk-java-release-")
        self.work, self.calls = Path(self.temp.name), 0
        self.assets = self.work / "assets"
        self.assets.mkdir()

    async def run(self, command: Sequence[str], extra: Mapping[str, str] = {}) -> bytes:
        """Run one trusted tool with a small explicit environment."""
        allowed = {"PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "JAVA_HOME", "CI"}
        env = {key: value for key, value in os.environ.items() if key in allowed} | dict(extra)
        return await asyncio.to_thread(subprocess.check_output, command, cwd=self.source, env=env)

    async def gh(self, *arguments: str) -> bytes:
        """Run GitHub CLI with only the short-lived App token."""
        return await self.run(("gh", *arguments), {"GH_TOKEN": self.github})

    async def request(
        self,
        url: str,
        *,
        method: str | None = None,
        auth: str = "",
        body: Any = None,
        upload: Path | None = None,
    ) -> tuple[int, bytes]:
        """Make an HTTP request while preserving status and response bytes."""
        self.calls += 1
        output = self.work / f"response-{self.calls}"
        command = [
            "curl",
            "-sSLo",
            str(output),
            "-w",
            "%{http_code}",
            "-L",
        ]
        if method:
            command += ["-X", method]
        if auth == "basic":
            command += ["-u", f"{self.user}:{self.password}"]
        elif auth:
            command += ["-H", f"Authorization: Bearer {auth}"]
        if body is not None:
            payload = self.work / f"request-{self.calls}.json"
            payload.write_text(json.dumps(body, separators=(",", ":")))
            command += ["-H", "Content-Type: application/json", "--data-binary", f"@{payload}"]
        if upload:
            command += ["--upload-file", str(upload)]
        status = int((await self.run((*command, url))).decode())
        return status, output.read_bytes()

    async def json(self, url: str, **options: Any) -> tuple[int, Any]:
        """Request and decode an optional JSON response."""
        status, data = await self.request(url, **options)
        return status, json.loads(data) if data else None

    # GitHub Actions artifact provenance and materialization ----------------

    async def artifact(self, expected_name: str, expected_file: str) -> Artifact:
        """Select the oldest live immutable-name artifact and freeze its identity."""
        page = json.loads(
            (
                await self.gh(
                    "api",
                    "--method",
                    "GET",
                    f"repos/{REPOSITORY}/actions/artifacts",
                    "-f",
                    f"name={expected_name}",
                    "-f",
                    "per_page=100",
                )
            ).decode()
        )
        matches = [item for item in page.get("artifacts", []) if item.get("name") == expected_name and item.get("expired") is False]
        if not matches:
            raise RuntimeError(f"Actions artifact is unavailable: {expected_name}")
        item = min(matches, key=lambda found: int(found["id"]))
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", str(item.get("digest"))):
            raise ReleaseError("Actions artifact has no immutable digest.")
        return Artifact(int(item["id"]), str(item["digest"]), expected_file)

    async def download(self, artifact: Artifact, destination: Path) -> Path:
        """Download and verify one frozen one-file Actions artifact."""
        status, archive = await self.request(
            f"https://api.github.com/repos/{REPOSITORY}/actions/artifacts/{artifact.id}/zip",
            auth=self.github,
        )
        if status != 200 or f"sha256:{hashlib.sha256(archive).hexdigest()}" != artifact.digest:
            raise ReleaseError("Actions artifact bytes differ from durable identity.")
        try:
            with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
                if bundle.namelist() != [artifact.file]:
                    raise ReleaseError("Actions artifact has unexpected contents.")
                destination.mkdir()
                target = destination / artifact.file
                with bundle.open(artifact.file) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output)
                return target
        except zipfile.BadZipFile as error:
            raise ReleaseError("Actions artifact is not a ZIP archive.") from error

    async def materialize(self) -> tuple[Path, list[tuple[str, str, int]]]:
        """Materialize native assets and the validated signed Maven repository."""
        maven: Artifact | None = None
        for index, artifact in enumerate(self.value.artifacts):
            if artifact.file == "maven-payload.tar":
                maven = artifact
            else:
                source = await self.download(artifact, self.work / f"native-{index}")
                shutil.copyfile(source, self.assets / artifact.file)
        if maven is None:
            raise ReleaseError("The Maven payload is missing.")
        archives = sorted(self.assets.iterdir())
        (self.assets / "SHA256SUMS").write_text(
            "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in archives)
        )
        archive = await self.download(maven, self.work / "maven")
        root, manifest = unpack_maven(archive, self.work / "bundle")
        records = validate_maven(root, manifest, self.candidate.maven, self.candidate, False)
        return root, records

    # Maven reconciliation ---------------------------------------------------

    async def central(self) -> int:
        """Count exact Central POM identities and reject partial/conflicting state."""
        present = 0
        for name in self.candidate.maven:
            status, pom = await self.request(f"{CENTRAL}/io/temporal/{name}/{self.candidate.version}/{name}-{self.candidate.version}.pom")
            if status == 404:
                continue
            if status != 200:
                raise RuntimeError(f"Maven Central returned HTTP {status} for {name}.")
            document = ET.fromstring(pom)
            ns = document.tag.partition("}")[0] + "}" if document.tag.startswith("{") else ""
            identity = tuple(document.findtext(f"{ns}{field}", "").strip() for field in ("groupId", "artifactId", "version"))
            identity += (document.findtext(f"{ns}scm/{ns}tag", "").strip().lower(),)
            if identity != ("io.temporal", name, self.candidate.version, self.candidate.commit):
                raise ReleaseError(f"Central coordinate {name} has another identity.")
            present += 1
        if present not in {0, len(self.candidate.maven)}:
            raise RuntimeError("Maven Central is partially visible.")
        return present

    def description(self, generation: int) -> str:
        """Return the idempotency description for one Maven generation."""
        return f"sdk-java:{self.candidate.id}:{generation}"

    async def view(self) -> View:
        """Read both Sonatype repository representations."""
        p_status, profiles = await self.json(f"{SONATYPE}/service/local/staging/profile_repositories", auth="basic")
        m_status, manual = await self.json(
            f"{SONATYPE}/manual/search/repositories?ip=any&profile_id=io.temporal",
            auth=self.portal_token,
        )
        p_items = profiles.get("data", profiles.get("profileRepositories", [])) if isinstance(profiles, dict) else []
        m_items = manual.get("repositories", []) if isinstance(manual, dict) else []
        if p_status != 200 or m_status != 200 or not isinstance(p_items, list) or not isinstance(m_items, list):
            raise RuntimeError("Sonatype repository state is unavailable.")
        return View([x for x in p_items if isinstance(x, dict)], [x for x in m_items if isinstance(x, dict)])

    async def create_repository(self, description: str) -> str:
        """Create the repository at Maven's inherently ambiguous boundary."""
        status, profiles = await self.json(f"{SONATYPE}/service/local/staging/profiles", auth="basic")
        matches = (
            [x.get("id") for x in profiles.get("data", []) if isinstance(x, dict) and x.get("name") == "io.temporal"]
            if isinstance(profiles, dict)
            else []
        )
        if status != 200 or len(matches) != 1:
            raise RuntimeError("The io.temporal Sonatype profile is unavailable.")
        status, response = await self.json(
            f"{SONATYPE}/service/local/staging/profiles/{matches[0]}/start",
            method="POST",
            auth="basic",
            body={"data": {"description": description}},
        )
        identity = (response.get("data") or {}).get("stagedRepositoryId") if isinstance(response, dict) else None
        if status not in {200, 201} or not isinstance(identity, str):
            raise MavenAmbiguous("Sonatype repository creation was ambiguous.")
        return identity

    async def portal_state(self, identity: str) -> str:
        """Read one exact Publisher Portal deployment state."""
        status, response = await self.json(f"{PORTAL}/status?id={identity}", method="POST", auth=self.portal_token)
        if status != 200 or not isinstance(response, dict) or response.get("deploymentId") != identity:
            raise RuntimeError("Publisher Portal deployment is unavailable.")
        return cast(str, response.get("deploymentState", ""))

    @staticmethod
    def repository_state(profiles: list[dict[str, Any]], manual: list[dict[str, Any]]) -> tuple[str, str | None]:
        """Derive the staging state and Portal ID from joined repository rows."""
        if manual:
            return str(manual[0].get("state", "")), cast(str | None, manual[0].get("portal_deployment_id"))
        return ("open", None) if profiles else ("absent", None)

    async def inspect(self) -> Inspection:
        """Observe every durable generation without mutating Maven."""
        present, view, found = await self.central(), await self.view(), []
        for durable in self.value.generations:
            identity = durable.repository or view.find(self.description(durable.number))
            profiles, manual = view.repository(identity) if identity else ([], [])
            state, portal = self.repository_state(profiles, manual)
            portal = durable.portal or portal
            found.append(
                Generation(
                    durable.number,
                    identity,
                    state,
                    portal,
                    await self.portal_state(portal) if portal else "",
                )
            )
        return Inspection(present, found)

    async def staging(self, repository: str, root: Path, records: list[tuple[str, str, int]]) -> None:
        """Idempotently upload the frozen payload into its still-open repository."""
        for relative, _, _ in records:
            status, _ = await self.request(
                f"{SONATYPE}/service/local/staging/deployByRepositoryId/{repository}/{relative}",
                auth="basic",
                upload=root / relative,
            )
            if not 200 <= status < 300:
                raise RuntimeError(f"Maven upload failed: {relative}")

    async def maven(self, root: Path, records: list[tuple[str, str, int]]) -> None:
        """Reconcile the current staging repository through Portal publication."""

        # Central is the terminal source of truth. If every exact POM is already
        # visible, a retry has nothing left to mutate in Sonatype or Portal.
        if await self.central() == len(self.candidate.maven):
            return
        current, view = self.value.generations[-1], await self.view()
        repository = current.repository or view.find(self.description(current.number))
        if not repository:
            for prior in self.value.generations[:-1]:
                old = prior.repository or view.find(self.description(prior.number))
                profiles, manual = view.repository(old) if old else ([], [])
                _, portal = self.repository_state(profiles, manual)
                portal_id = prior.portal or portal
                state = await self.portal_state(portal_id) if portal_id else ""
                if profiles or manual and (manual[0].get("state") != "released" or state != "FAILED"):
                    raise MavenAmbiguous("An earlier Maven generation is still active.")
            await self.create_repository(self.description(current.number))
            raise MavenAmbiguous("Sonatype repository created; adopting its identity.")
        profiles, manual = (await self.view()).repository(repository)
        state, portal = self.repository_state(profiles, manual)
        if state == "open":
            await self.staging(repository, root, records)
            status, _ = await self.request(
                f"{SONATYPE}/service/local/staging/bulk/close",
                method="POST",
                auth="basic",
                body={
                    "data": {
                        "stagedRepositoryIds": [repository],
                        "description": self.description(current.number),
                    }
                },
            )
            if status not in {200, 201, 202, 204}:
                raise RuntimeError("Sonatype repository close failed.")
            raise RuntimeError("Sonatype close accepted; reconciling.")
        if state not in {"closed", "released"} or not portal:
            raise RuntimeError("Closed repository is not visible in Portal yet.")
        portal_state = await self.portal_state(portal)
        if portal_state == "VALIDATED":
            status, _ = await self.request(f"{PORTAL}/deployment/{portal}", method="POST", auth=self.portal_token)
            if status != 204:
                raise RuntimeError("Portal publication failed.")
            raise RuntimeError("Portal publication accepted; reconciling.")
        if portal_state in {"PENDING", "VALIDATING", "PUBLISHING"}:
            raise RuntimeError(f"Portal deployment is {portal_state}.")
        if portal_state == "FAILED":
            raise MavenFailed("Portal validation failed.")
        if portal_state != "PUBLISHED":
            raise ReleaseError(f"Unsupported Portal state: {portal_state}")

    # GitHub release reconciliation -----------------------------------------

    async def tag(self) -> None:
        """Create the release tag or require its exact commit target."""
        status, data = await self.json(
            f"https://api.github.com/repos/{REPOSITORY}/git/ref/tags/{self.candidate.tag}",
            auth=self.github,
        )
        if status == 404:
            await self.gh(
                "api",
                "--method",
                "POST",
                f"repos/{REPOSITORY}/git/refs",
                "-f",
                f"ref=refs/tags/{self.candidate.tag}",
                "-f",
                f"sha={self.candidate.commit}",
            )
            return
        target = data.get("object", {}) if isinstance(data, dict) else {}
        if status != 200 or (target.get("type"), target.get("sha")) != (
            "commit",
            self.candidate.commit,
        ):
            raise ReleaseError("The Git tag points at another commit.")

    async def release(self) -> dict[str, Any] | None:
        """Read the release for this exact tag, including authenticated drafts."""
        status, data = await self.json(f"https://api.github.com/repos/{REPOSITORY}/releases?per_page=100", auth=self.github)
        if status != 200 or not isinstance(data, list):
            raise RuntimeError("GitHub release is unavailable.")
        return next(
            (cast(dict[str, Any], item) for item in data if isinstance(item, dict) and item.get("tag_name") == self.candidate.tag),
            None,
        )

    async def github_release(self) -> ReleaseResult:
        """Publish GitHub only after Maven Central and draft assets are complete."""
        if await self.central() != len(self.candidate.maven):
            raise RuntimeError("Maven Central is incomplete.")
        await self.tag()
        release = await self.release()
        if release is None:
            arguments = [
                "release",
                "create",
                self.candidate.tag,
                "--repo",
                REPOSITORY,
                "--draft",
                "--target",
                self.candidate.commit,
                "--title",
                self.candidate.tag,
                "--notes-file",
                str(self.source / "releases" / self.candidate.tag),
            ]
            if "-RC" in self.candidate.tag:
                arguments.append("--prerelease")
            await self.gh(*arguments)
            release = await self.release()
        if release is None:
            raise RuntimeError("GitHub draft is not visible.")
        if release.get("target_commitish") != self.candidate.commit:
            raise ReleaseError("GitHub release targets another commit.")
        expected = {path.name: path for path in self.assets.iterdir()}
        present = {item.get("name") for item in release.get("assets", []) if isinstance(item, dict)}
        if release.get("draft") is True:
            # Draft-first publication prevents a public release from appearing
            # before every native archive and checksum file is attached.
            for name in expected.keys() - present:
                await self.gh(
                    "release",
                    "upload",
                    self.candidate.tag,
                    str(expected[name]),
                    "--repo",
                    REPOSITORY,
                )
            release = await self.release()
            if release is None or expected.keys() - {item.get("name") for item in release.get("assets", []) if isinstance(item, dict)}:
                raise RuntimeError("GitHub draft assets are incomplete.")
            await self.gh("release", "edit", self.candidate.tag, "--repo", REPOSITORY, "--draft=false")
            release = await self.release()
        if release is None or release.get("draft") is not False:
            raise RuntimeError("GitHub release is not public.")
        if expected.keys() - {item.get("name") for item in release.get("assets", []) if isinstance(item, dict)}:
            raise ReleaseError("The public GitHub release is missing assets.")
        return ReleaseResult(
            digest([asdict(self.candidate), [asdict(item) for item in self.value.artifacts]]),
            cast(str, release.get("html_url", "")),
            f"https://central.sonatype.com/artifact/io.temporal/temporal-sdk/{self.candidate.version}",
        )


# Temporal Activities --------------------------------------------------------


class ReleaseActivities:
    def __init__(self, source: Path, environment: Mapping[str, str]) -> None:
        """Capture the protected publication job's source and environment."""
        self.source, self.environment = source, dict(environment)

    async def _run(self, value: ReleaseInput, operation: str) -> Any:
        """Map durable external outcomes to non-retryable Temporal errors."""
        session = Session(value, self.source, self.environment)
        try:
            return await getattr(session, operation)()
        except ReleaseError as error:
            raise ApplicationError(str(error), type=error.error_type, non_retryable=True) from error
        finally:
            session.temp.cleanup()

    @activity.defn(name="discoverArtifacts")
    async def discover(self, value: ReleaseInput) -> list[Artifact]:
        """Freeze deterministic native and Maven Actions artifact identities."""
        session = Session(value, self.source, self.environment)
        try:
            artifacts = [
                await session.artifact(
                    f"sdk-java-release-native-{value.candidate.id}-{platform}",
                    native_file(value.candidate, platform),
                )
                for platform in PLATFORMS
            ]
            artifacts.append(await session.artifact(f"sdk-java-release-maven-{value.candidate.id}", "maven-payload.tar"))
            return artifacts
        finally:
            session.temp.cleanup()

    @activity.defn(name="inspectMaven")
    async def inspect_maven(self, value: ReleaseInput) -> Inspection:
        """Inspect Maven generations without creating external state."""
        return cast(Inspection, await self._run(value, "inspect"))

    @activity.defn(name="publishRelease")
    async def publish(self, value: ReleaseInput) -> ReleaseResult:
        """Reconcile Maven, then the GitHub draft, then public GitHub state."""
        session = Session(value, self.source, self.environment)
        try:
            root, records = await session.materialize()
            await session.maven(root, records)
            return await session.github_release()
        except ReleaseError as error:
            raise ApplicationError(str(error), type=error.error_type, non_retryable=True) from error
        finally:
            session.temp.cleanup()


# Merge-triggered command-line entry points ---------------------------------


def required(name: str) -> str:
    """Read one required CLI environment value."""
    if not (value := os.environ.get(name)):
        raise ValueError(f"Missing required value: {name}")
    return value


def candidate_from_push() -> Candidate:
    """Turn one newly merged release-note file into the approved identity."""
    commit, base = required("RELEASE_COMMIT"), required("BASE_SHA")
    base = subprocess.check_output(["git", "rev-parse", "HEAD^"], text=True).strip() if re.fullmatch(r"0+", base) else base
    if subprocess.check_output(["git", "rev-parse", "HEAD^{commit}"], text=True).strip() != commit:
        raise ValueError("The checkout is not the merged commit.")
    changed = subprocess.check_output(
        ["git", "diff", "--name-status", "--no-renames", base, commit, "--", "releases/"], text=True
    ).splitlines()
    if len(changed) != 1 or not (match := re.fullmatch(r"A\s+(releases/(v.+))", changed[0])):
        raise ValueError("The merge must add exactly one release-note file.")
    projects = re.findall(r"(?m)^include ['\"]([^'\"]+)['\"]$", Path("settings.gradle").read_text())
    policies = [policy for policy in MAVEN_POLICIES if len(projects) == len(policy) and set(projects) == set(policy)]
    if len(policies) != 1:
        raise ValueError("The Gradle project set is not a fixed Maven policy.")
    candidate = Candidate(match.group(2), commit, policies[0])
    candidate.validate()
    if not Path(match.group(1)).is_file():
        raise ValueError("Release notes are unavailable.")
    return candidate


async def connect() -> Client:
    """Connect to the configured Temporal namespace."""
    return await Client.connect(
        required("TEMPORAL_ADDRESS"),
        namespace=required("TEMPORAL_NAMESPACE"),
        api_key=required("TEMPORAL_API_KEY"),
        tls=True,
    )


def output(name: str, value: Any) -> None:
    """Write an Actions output or readable local output."""
    text = json.dumps(value, separators=(",", ":")) if isinstance(value, (list, dict)) else str(value)
    if destination := os.environ.get("GITHUB_OUTPUT"):
        with Path(destination).open("a") as stream:
            stream.write(f"{name}={text}\n")
    else:
        print(f"{name}={text}")


async def main() -> None:
    """Dispatch the two commands used by the merge-triggered Actions workflow."""
    client = await connect()
    match sys.argv[1:]:
        case ["start"]:
            # The lightweight start job validates the push and creates (or finds)
            # the one Workflow execution for this immutable candidate.
            candidate = candidate_from_push()
            handle = await client.start_workflow(
                "ReleaseWorkflow",
                candidate,
                id=workflow_id(candidate),
                task_queue=workflow_queue(candidate.id),
                id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
                id_conflict_policy=WorkflowIDConflictPolicy.USE_EXISTING,
            )
            output("run_id", handle.result_run_id)
            output("candidate_id", candidate.id)
            output("tag", candidate.tag)
            output("version", candidate.version)
            output("maven_artifacts", candidate.maven)
        case ["publish", identity, run_id]:
            # The protected publication job hosts one Workflow Worker and both
            # possible Maven-generation Activity Workers. They exist only for the
            # duration of this job and listen only on this release's task queues.
            activities = ReleaseActivities(Path(required("RELEASE_SOURCE_DIR")), os.environ)
            workers = [
                Worker(client, task_queue=workflow_queue(identity), workflows=[ReleaseWorkflow]),
                *[
                    Worker(
                        client,
                        task_queue=publication_queue(identity, generation),
                        activities=[
                            activities.discover,
                            activities.inspect_maven,
                            activities.publish,
                        ],
                    )
                    for generation in (0, 1)
                ],
            ]
            async with workers[0], workers[1], workers[2]:
                handle = client.get_workflow_handle(f"sdk-java-release/{identity}", run_id=run_id)
                await handle.execute_update("buildsReady")
                await handle.result()
        case _:
            raise ValueError("Expected start or publish command.")


if __name__ == "__main__":
    asyncio.run(main())
