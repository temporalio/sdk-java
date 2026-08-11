import base64
import hashlib
import io
import json
import re
import shutil
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from collections.abc import Awaitable, Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, NamedTuple, cast

from .maven_payload import extract, validate
from .models import (
    REPOSITORY,
    GithubArtifactReceipt,
    MavenGeneration,
    MavenInspection,
    PublicationInput,
    ReleaseResult,
    maven_artifacts,
)

CENTRAL = "https://repo1.maven.org/maven2"
SONATYPE = "https://ossrh-staging-api.central.sonatype.com"
PORTAL = "https://central.sonatype.com/api/v1/publisher"
SAFE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
BRANCH = re.compile(r"(?:main|releases/.+|[^/]*\.[^/]*\.x|release_[^/]*_[^/]*_x)")
RunCommand = Callable[[Sequence[str], Mapping[str, str]], Awaitable[bytes]]


class PublicationFailure(RuntimeError):
    """A durable publication outcome that Temporal must not retry as transient."""

    error_type = ""


class Conflict(PublicationFailure):
    """External identity or bytes conflict with the immutable release."""

    error_type = "ReleaseIdentityConflict"


class MavenAmbiguous(PublicationFailure):
    """A Maven mutation may have succeeded and requires Workflow inspection."""

    error_type = "MavenSubmissionAmbiguous"


class MavenFailed(PublicationFailure):
    """The exact Portal deployment reached terminal validation failure."""

    error_type = "MavenDeploymentFailed"


class ArtifactUnavailable(PublicationFailure):
    """A receipted GitHub Actions artifact expired or was deleted."""

    error_type = "ArtifactUnavailable"


class MavenFile(NamedTuple):
    """One exact path, digest, and size from the frozen Maven manifest."""

    relative: str
    digest: str
    size: int


class RepositoryView(NamedTuple):
    """The matching entries for one repository across both Sonatype APIs."""

    profiles: list[dict[str, Any]]
    manual: list[dict[str, Any]]


class SonatypeView(NamedTuple):
    """A consistent-enough joined snapshot of legacy staging and Portal state."""

    profiles: list[dict[str, Any]]
    manual: list[dict[str, Any]]

    def repository(self, repository_id: str) -> RepositoryView:
        """Return every entry bearing one exact repository ID."""
        return RepositoryView(
            [x for x in self.profiles if (x.get("repositoryId") or x.get("id")) == repository_id],
            [x for x in self.manual if x.get("key") == repository_id],
        )

    def find(self, description: str) -> str | None:
        """Discover at most one repository with a generation-specific description."""
        found = {
            cast(str, x.get("repositoryId") or x.get("id"))
            for x in self.profiles
            if x.get("description") == description and (x.get("repositoryId") or x.get("id"))
        } | {
            cast(str, x["key"])
            for x in self.manual
            if x.get("description") == description and x.get("key")
        }
        if len(found) > 1:
            raise Conflict("Multiple Sonatype repositories match one Maven generation.")
        return next(iter(found), None)


class Publisher:
    """Reconcile one typed release against GitHub, Sonatype, and Maven Central."""

    def __init__(
        self,
        value: PublicationInput,
        source_root: Path,
        environment: Mapping[str, str],
        run: RunCommand,
    ) -> None:
        """Bind immutable input, explicit credentials, and the trusted process runner."""
        self.value = value
        self.source_root = source_root
        self.run = run
        self.github_token = self._required(environment, "GH_TOKEN")
        self.user = self._required(environment, "RH_USER")
        self.password = self._required(environment, "RH_PASSWORD")
        self.portal_token = base64.b64encode(f"{self.user}:{self.password}".encode()).decode()
        self.release = value.release
        self.candidate = self.release.candidate
        self.tag = self.candidate.tag
        self.version = self.candidate.version
        self.commit = self.candidate.commitSha
        self.digest = self.release.digest()
        self.artifacts = maven_artifacts(self.candidate.mavenPolicy)
        self.generation = value.mavenGenerations[-1].generation
        self.notes = source_root / "releases" / self.tag
        self.work_context = tempfile.TemporaryDirectory(prefix="temporal-release-")
        self.work = Path(self.work_context.name)
        self.assets = self.work / "assets"
        self.assets.mkdir()
        self.request_number = 0
        self._verify_source()

    def close(self) -> None:
        """Remove temporary payloads, API responses, and downloaded artifacts."""
        self.work_context.cleanup()

    @staticmethod
    def _required(environment: Mapping[str, str], name: str) -> str:
        """Read one required protected Actions value."""
        value = environment.get(name)
        if not value:
            raise RuntimeError(f"Required publication value is missing: {name}")
        return value

    def _verify_source(self) -> None:
        """Require the immutable source checkout and its exact release notes."""
        commit = subprocess.check_output(
            ["git", "rev-parse", "--verify", "HEAD^{commit}"], cwd=self.source_root, text=True
        ).strip()
        if commit != self.commit:
            raise Conflict("The source checkout is not the immutable release commit.")
        if not self.notes.is_file() or self.notes.is_symlink() or not self.notes.stat().st_size:
            raise Conflict("The release notes are unavailable.")

    async def _gh(self, *arguments: str) -> bytes:
        """Run GitHub CLI with only the short-lived App token."""
        return await self.run(("gh", *arguments), {"GH_TOKEN": self.github_token})

    async def _gh_json(self, *arguments: str) -> Any:
        """Run GitHub CLI and decode its JSON response."""
        return json.loads((await self._gh(*arguments)).decode())

    async def _request(
        self,
        url: str,
        *,
        method: str | None = None,
        basic: bool = False,
        bearer: str | None = None,
        body: Any = None,
        upload: Path | None = None,
        head: bool = False,
    ) -> tuple[int, bytes]:
        """Make one status-preserving HTTP request through trusted curl."""
        self.request_number += 1
        response = self.work / f"response-{self.request_number}"
        command = [
            "curl",
            "--silent",
            "--show-error",
            "--location",
            "--output",
            str(response),
            "--write-out",
            "%{http_code}",
        ]
        if method:
            command += ["--request", method]
        if head:
            command.append("--head")
        if basic:
            command += ["--user", f"{self.user}:{self.password}"]
        if bearer:
            command += ["--header", f"Authorization: Bearer {bearer}"]
        if body is not None:
            body_file = self.work / f"request-{self.request_number}.json"
            body_file.write_text(json.dumps(body, separators=(",", ":")))
            command += [
                "--header",
                "Content-Type: application/json",
                "--data-binary",
                f"@{body_file}",
            ]
        if upload:
            command += ["--upload-file", str(upload)]
        status = int((await self.run((*command, url), {})).decode())
        return status, response.read_bytes()

    async def _json_request(self, url: str, **options: Any) -> tuple[int, Any]:
        """Make an HTTP request and decode a nonempty JSON body."""
        status, body = await self._request(url, **options)
        try:
            return status, json.loads(body) if body else None
        except json.JSONDecodeError as error:
            raise RuntimeError(f"Remote service returned invalid JSON for {url}.") from error

    async def download_artifact(self, receipt: GithubArtifactReceipt, destination: Path) -> Path:
        """Download and verify one exact receipt-backed Actions artifact."""
        if (
            receipt.artifactId <= 0
            or receipt.workflowRunId <= 0
            or not SAFE_NAME.fullmatch(receipt.artifactName)
            or not SAFE_NAME.fullmatch(receipt.fileName)
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", receipt.githubDigest)
        ):
            raise Conflict("Invalid Temporal artifact receipt.")
        status, metadata = await self._json_request(
            f"https://api.github.com/repos/{REPOSITORY}/actions/artifacts/{receipt.artifactId}",
            bearer=self.github_token,
        )
        if status == 404:
            raise ArtifactUnavailable(f"Artifact {receipt.artifactId} was deleted.")
        if status != 200 or not isinstance(metadata, dict):
            raise RuntimeError(f"GitHub returned HTTP {status} for artifact metadata.")
        expected = (
            receipt.artifactId,
            receipt.workflowRunId,
            receipt.artifactName,
            receipt.githubDigest,
        )
        actual = (
            metadata.get("id"),
            (metadata.get("workflow_run") or {}).get("id"),
            metadata.get("name"),
            metadata.get("digest"),
        )
        if actual != expected:
            raise Conflict("GitHub artifact metadata changed.")
        if metadata.get("expired") is not False:
            raise ArtifactUnavailable(f"Artifact {receipt.artifactId} has expired.")
        run = await self._gh_json("api", f"repos/{REPOSITORY}/actions/runs/{receipt.workflowRunId}")
        if not isinstance(run, dict) or not self._valid_artifact_run(run, receipt.workflowRunId):
            raise Conflict("The artifact originated from another workflow run.")
        status, archive = await self._request(
            f"https://api.github.com/repos/{REPOSITORY}/actions/artifacts/{receipt.artifactId}/zip",
            bearer=self.github_token,
        )
        if status in {404, 410}:
            raise ArtifactUnavailable("The exact artifact has no downloadable archive.")
        if status != 200:
            raise RuntimeError(f"GitHub returned HTTP {status} for artifact download.")
        if f"sha256:{hashlib.sha256(archive).hexdigest()}" != receipt.githubDigest:
            raise Conflict("GitHub artifact archive digest changed.")
        destination.mkdir()
        try:
            with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
                names = bundle.namelist()
                if names != [receipt.fileName] or not SAFE_NAME.fullmatch(receipt.fileName):
                    raise Conflict("GitHub artifact contents differ from its receipt.")
                target = destination / receipt.fileName
                with bundle.open(receipt.fileName) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output)
        except zipfile.BadZipFile as error:
            raise Conflict("GitHub artifact archive is not a ZIP file.") from error
        return destination / receipt.fileName

    @staticmethod
    def _valid_artifact_run(run: Mapping[str, Any], run_id: int) -> bool:
        """Preserve the accepted merge-workflow provenance boundary."""
        repository = run.get("head_repository")
        return (
            run.get("id") == run_id
            and run.get("path") == ".github/workflows/temporal-release-candidate.yml"
            and run.get("event") == "push"
            and isinstance(repository, Mapping)
            and repository.get("full_name") == REPOSITORY
            and BRANCH.fullmatch(str(run.get("head_branch", ""))) is not None
            and run.get("status") in {"in_progress", "completed"}
        )

    async def materialize_native_assets(self) -> None:
        """Materialize the fixed native matrix and deterministic checksum asset."""
        for index, receipt in enumerate(self.release.artifacts):
            source = await self.download_artifact(receipt, self.work / f"native-{index}")
            shutil.copyfile(source, self.assets / receipt.fileName)
        archives = sorted(
            path for path in self.assets.iterdir() if path.name.endswith((".tar.gz", ".zip"))
        )
        lines = [
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in archives
        ]
        (self.assets / "SHA256SUMS").write_text("".join(lines))

    async def materialize_maven_payload(self) -> tuple[Path, list[MavenFile]]:
        """Download, safely extract, and validate the frozen signed Maven payload."""
        assert self.value.mavenPayload is not None
        archive = await self.download_artifact(
            self.value.mavenPayload, self.work / "maven-download"
        )
        bundle = self.work / "maven-bundle"
        bundle.mkdir()
        try:
            extract(archive, bundle)
            root, manifest = bundle / "repository", bundle / "manifest.tsv"
            validate(root, manifest, self.artifacts, self.version, self.commit, False)
            records = [
                MavenFile(path, digest, int(size))
                for path, digest, size in (
                    line.split("\t") for line in manifest.read_text().splitlines()
                )
            ]
        except (OSError, ValueError, ET.ParseError, tarfile.TarError) as error:
            raise Conflict("The Maven archive violates sdk-java policy.") from error
        return root, records

    async def central_state(self) -> tuple[int, int]:
        """Count expected Central POMs and reject conflicting immutable identities."""
        present = missing = 0
        for artifact in self.artifacts:
            status, pom = await self._request(
                f"{CENTRAL}/io/temporal/{artifact}/{self.version}/{artifact}-{self.version}.pom"
            )
            if status == 404:
                missing += 1
                continue
            if status != 200:
                raise RuntimeError(f"Maven Central returned HTTP {status} for {artifact}.")
            try:
                document = ET.fromstring(pom)
            except ET.ParseError as error:
                raise RuntimeError(
                    f"Maven Central returned an invalid POM for {artifact}."
                ) from error
            namespace = document.tag.partition("}")[0] + "}" if document.tag.startswith("{") else ""
            identity = tuple(
                document.findtext(f"{namespace}{field}", "").strip()
                for field in ("groupId", "artifactId", "version")
            ) + (document.findtext(f"{namespace}scm/{namespace}tag", "").strip().lower(),)
            if identity != ("io.temporal", artifact, self.version, self.commit):
                raise Conflict(f"{artifact} exists with another immutable identity.")
            present += 1
        return present, missing

    async def validate_central_files(self, records: list[MavenFile]) -> None:
        """Require every signed manifest entry to be visible in Maven Central."""
        for record in records:
            status, _ = await self._request(f"{CENTRAL}/{record.relative}", head=True)
            if status != 200:
                raise RuntimeError(f"Maven Central returned HTTP {status} for {record.relative}.")

    async def sonatype_view(self) -> SonatypeView:
        """Join legacy staging and Publisher Portal repository snapshots."""
        status, profiles = await self._json_request(
            f"{SONATYPE}/service/local/staging/profile_repositories", basic=True
        )
        if status != 200 or not isinstance(profiles, dict):
            raise RuntimeError("Sonatype repositories are unavailable.")
        profile_entries = profiles.get("data", profiles.get("profileRepositories"))
        status, manual = await self._json_request(
            f"{SONATYPE}/manual/search/repositories?ip=any&profile_id=io.temporal",
            bearer=self.portal_token,
        )
        if status != 200 or not isinstance(manual, dict):
            raise RuntimeError("Publisher Portal state is unavailable.")
        manual_entries = manual.get("repositories", [])
        if not isinstance(profile_entries, list) or not isinstance(manual_entries, list):
            raise RuntimeError("Sonatype repository data is invalid.")
        return SonatypeView(
            [cast(dict[str, Any], x) for x in profile_entries if isinstance(x, dict)],
            [cast(dict[str, Any], x) for x in manual_entries if isinstance(x, dict)],
        )

    def description(self, generation: int) -> str:
        """Return the exact Sonatype idempotency description for one generation."""
        return f"sdk-java:{self.digest}:{generation}"

    async def create_repository(self, description: str) -> str:
        """Create one staging repository at the inherently ambiguous response boundary."""
        status, profiles = await self._json_request(
            f"{SONATYPE}/service/local/staging/profiles", basic=True
        )
        if status != 200 or not isinstance(profiles, dict):
            raise RuntimeError("Sonatype profiles are temporarily unavailable.")
        matches = [
            x.get("id")
            for x in profiles.get("data", [])
            if isinstance(x, dict) and x.get("name") == "io.temporal"
        ]
        if len(matches) != 1 or not isinstance(matches[0], str):
            raise Conflict("Sonatype did not return one fixed io.temporal profile.")
        status, response = await self._json_request(
            f"{SONATYPE}/service/local/staging/profiles/{matches[0]}/start",
            method="POST",
            basic=True,
            body={"data": {"description": description}},
        )
        if status not in {200, 201} or not isinstance(response, dict):
            raise RuntimeError(f"Sonatype returned HTTP {status} while creating the repository.")
        repository_id = (response.get("data") or {}).get("stagedRepositoryId")
        if not isinstance(repository_id, str) or not SAFE_NAME.fullmatch(repository_id):
            raise RuntimeError("Sonatype accepted repository creation without returning an ID.")
        return repository_id

    async def portal_status(self, deployment_id: str) -> str:
        """Read and identity-check one exact Publisher Portal deployment."""
        status, response = await self._json_request(
            f"{PORTAL}/status?id={deployment_id}", method="POST", bearer=self.portal_token
        )
        if status != 200 or not isinstance(response, dict):
            raise RuntimeError("Publisher Portal deployment is unavailable.")
        if response.get("deploymentId") != deployment_id or not isinstance(
            response.get("deploymentState"), str
        ):
            raise RuntimeError("Publisher Portal returned an invalid deployment identity.")
        return cast(str, response["deploymentState"])

    async def validate_prior_generations_inactive(self, view: SonatypeView) -> None:
        """Close the final race before creating the one replacement generation."""
        for generation in self.value.mavenGenerations:
            if not generation.submissionStarted or generation.generation >= self.generation:
                continue
            discovered = view.find(self.description(generation.generation))
            if generation.repositoryId and discovered and generation.repositoryId != discovered:
                raise Conflict("An earlier Maven generation has another repository identity.")
            repository_id = generation.repositoryId or discovered
            repository = view.repository(repository_id) if repository_id else RepositoryView([], [])
            portal_id = generation.portalDeploymentId or self._portal_id(repository)
            state = await self.portal_status(portal_id) if portal_id else ""
            if state not in {"", "FAILED"}:
                raise MavenAmbiguous(
                    f"Earlier Maven generation {generation.generation} has Portal state {state}."
                )
            if not repository_id:
                continue
            if repository.profiles:
                raise MavenAmbiguous("An earlier Maven generation is still staged.")
            if state == "FAILED":
                exact = (
                    len(repository.manual) == 1
                    and repository.manual[0].get("state") == "released"
                    and repository.manual[0].get("portal_deployment_id") == portal_id
                )
                if not exact:
                    raise MavenAmbiguous("A failed Maven generation is not inactive.")
            elif repository.manual:
                raise MavenAmbiguous("An earlier Maven generation is still live.")

    async def reconcile_repository(self, present: int) -> str:
        """Adopt or create the repository for the current durable generation."""
        if present not in {0, len(self.artifacts)}:
            raise RuntimeError("Maven publication is partially visible.")
        current = self.value.mavenGenerations[-1]
        if not current.submissionStarted:
            raise Conflict("The durable Maven generation intent differs.")
        view = await self.sonatype_view()
        repository_id = current.repositoryId or view.find(self.description(self.generation))
        if repository_id:
            return repository_id
        if present:
            raise MavenAmbiguous("Maven Central is complete without a repository identity.")
        await self.validate_prior_generations_inactive(view)
        return await self.create_repository(self.description(self.generation))

    async def inspect_staging(
        self, repository_id: str, root: Path, records: list[MavenFile]
    ) -> list[MavenFile]:
        """Return absent staged files and reject any existing byte mismatch."""
        missing = []
        for record in records:
            status, remote = await self._request(
                f"{SONATYPE}/service/local/repositories/{repository_id}/content/{record.relative}",
                basic=True,
            )
            if status == 404:
                missing.append(record)
            elif status != 200:
                raise RuntimeError(f"Sonatype returned HTTP {status} for {record.relative}.")
            elif len(remote) != record.size or hashlib.sha256(remote).hexdigest() != record.digest:
                raise Conflict(f"Staged Maven file {record.relative} differs.")
        return missing

    async def upload_staging(
        self, repository_id: str, root: Path, records: list[MavenFile]
    ) -> None:
        """Upload only manifest entries proven absent from the exact repository."""
        for record in records:
            status, _ = await self._request(
                f"{SONATYPE}/service/local/staging/deployByRepositoryId/{repository_id}/{record.relative}",
                basic=True,
                upload=root / record.relative,
            )
            if status < 200 or status >= 300:
                raise RuntimeError(f"Unable to upload staged Maven file {record.relative}.")

    async def reconcile_portal(
        self, repository_id: str, root: Path, records: list[MavenFile]
    ) -> str:
        """Make staging exact, close it, and return its Portal deployment ID."""
        description = self.description(self.generation)
        repository = (await self.sonatype_view()).repository(repository_id)
        profile_description = (
            repository.profiles[0].get("description") if repository.profiles else ""
        )
        state = repository.manual[0].get("state") if repository.manual else ""
        portal_id = self._portal_id(repository)
        if profile_description and profile_description != description:
            raise Conflict("The Sonatype repository ID has another description.")
        if not state:
            if not profile_description:
                raise MavenAmbiguous("The repository disappeared from Sonatype.")
            state = "open"
        if state == "open":
            missing = await self.inspect_staging(repository_id, root, records)
            await self.upload_staging(repository_id, root, missing)
            if await self.inspect_staging(repository_id, root, records):
                raise RuntimeError("The staged Maven payload is incomplete.")
            status, _ = await self._request(
                f"{SONATYPE}/service/local/staging/bulk/close",
                method="POST",
                basic=True,
                body={"data": {"stagedRepositoryIds": [repository_id], "description": description}},
            )
            if status not in {200, 201, 202, 204}:
                raise RuntimeError(f"Sonatype returned HTTP {status} while closing the repository.")
            raise RuntimeError("Sonatype accepted the repository close; Temporal will reconcile.")
        if state not in {"closed", "released"}:
            raise Conflict(f"Sonatype returned unsupported repository state {state}.")
        if not portal_id or not re.fullmatch(r"[0-9a-fA-F-]{16,64}", portal_id):
            raise RuntimeError("The Portal deployment ID is not visible yet.")
        return portal_id

    @staticmethod
    def _portal_id(repository: RepositoryView) -> str | None:
        """Return the first Portal deployment ID exposed for one repository."""
        value = repository.manual[0].get("portal_deployment_id") if repository.manual else None
        return value if isinstance(value, str) and value else None

    async def publish_maven(self, portal_id: str) -> None:
        """Publish a validated Portal deployment and force fresh reconciliation."""
        state = await self.portal_status(portal_id)
        if state == "VALIDATED":
            status, _ = await self._request(
                f"{PORTAL}/deployment/{portal_id}", method="POST", bearer=self.portal_token
            )
            if status != 204:
                raise RuntimeError(f"Portal returned HTTP {status} while publishing.")
            raise RuntimeError("Portal accepted publication; Temporal will reconcile again.")
        if state in {"PENDING", "VALIDATING", "PUBLISHING"}:
            raise RuntimeError(f"Portal deployment is {state}.")
        if state == "FAILED":
            raise MavenFailed("The exact Portal deployment failed validation.")
        if state != "PUBLISHED":
            raise Conflict(f"Portal returned unsupported deployment state {state}.")

    async def release_json(self) -> dict[str, Any] | None:
        """Fetch the release matching the exact tag, including authenticated drafts."""
        pages = await self._gh_json(
            "api", "--paginate", "--slurp", f"repos/{REPOSITORY}/releases?per_page=100"
        )
        if not isinstance(pages, list):
            raise RuntimeError("GitHub releases returned invalid data.")
        releases = [item for page in pages if isinstance(page, list) for item in page]
        return next(
            (
                cast(dict[str, Any], item)
                for item in releases
                if isinstance(item, dict) and item.get("tag_name") == self.tag
            ),
            None,
        )

    async def tag_json(self) -> dict[str, Any] | None:
        """Read an exact Git tag while distinguishing absence from API failure."""
        status, tag = await self._json_request(
            f"https://api.github.com/repos/{REPOSITORY}/git/ref/tags/{self.tag}",
            bearer=self.github_token,
        )
        if status == 404:
            return None
        if status != 200 or not isinstance(tag, dict):
            raise RuntimeError(f"GitHub returned HTTP {status} while reading the tag.")
        return cast(dict[str, Any], tag)

    def verify_tag(self, tag: Mapping[str, Any]) -> None:
        """Require an existing tag to be the expected lightweight commit ref."""
        target = tag.get("object")
        if not isinstance(target, Mapping) or (target.get("type"), target.get("sha")) != (
            "commit",
            self.commit,
        ):
            raise Conflict("The Git tag points at another object.")

    async def ensure_tag(self) -> None:
        """Create the exact tag if absent, reconciling a concurrent creation."""
        if tag := await self.tag_json():
            self.verify_tag(tag)
            return
        try:
            await self._gh(
                "api",
                "--method",
                "POST",
                f"repos/{REPOSITORY}/git/refs",
                "--raw-field",
                f"ref=refs/tags/{self.tag}",
                "--raw-field",
                f"sha={self.commit}",
            )
        except subprocess.CalledProcessError:
            tag = await self.tag_json()
            if tag is None:
                raise RuntimeError("The exact Git tag could not be reconciled.") from None
            self.verify_tag(tag)

    def verify_release(self, release: Mapping[str, Any], draft: bool) -> None:
        """Verify immutable GitHub release metadata and publication state."""
        expected = (
            self.tag,
            self.tag,
            self.notes.read_text(),
            draft,
            "-RC" in self.tag,
            self.commit,
        )
        actual = tuple(
            release.get(field)
            for field in ("tag_name", "name", "body", "draft", "prerelease", "target_commitish")
        )
        if actual != expected:
            raise Conflict("GitHub release metadata differs.")

    def expected_assets(self) -> dict[str, Path]:
        """Return the complete local release asset set by public filename."""
        return {path.name: path for path in self.assets.iterdir() if path.is_file()}

    def inspect_assets(self, release: Mapping[str, Any], exact: bool) -> set[str]:
        """Validate present assets and return expected names that remain absent.

        Draft preflight deliberately checks names only; detailed byte reconciliation
        remains after Maven begins. Later and public checks compare state, size, and
        GitHub's SHA-256 digest before allowing publication.
        """
        expected = self.expected_assets()
        assets = release.get("assets")
        if not isinstance(assets, list):
            raise Conflict("GitHub release asset metadata is invalid.")
        present: set[str] = set()
        for item in assets:
            if not isinstance(item, dict) or not isinstance(item.get("name"), str):
                raise Conflict("GitHub returned invalid release asset metadata.")
            name = cast(str, item["name"])
            if name in present or name not in expected:
                raise Conflict(f"The release has unexpected asset {name}.")
            present.add(name)
            path = expected[name]
            matches = (
                item.get("state") == "uploaded"
                and item.get("size") == path.stat().st_size
                and item.get("digest") == f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"
            )
            if exact and not matches:
                raise Conflict(f"GitHub asset {path.name} differs.")
        return set(expected) - present

    def verify_assets(self, release: Mapping[str, Any]) -> None:
        """Require the complete exact asset set before and after publication."""
        if self.inspect_assets(release, True):
            raise RuntimeError("The GitHub asset set is not complete.")

    async def verify_github_preflight(self) -> None:
        """Reject conflicting tags or releases before any Maven-side mutation."""
        if tag := await self.tag_json():
            self.verify_tag(tag)
        release = await self.release_json()
        if release is None:
            return
        draft = release.get("draft")
        if not isinstance(draft, bool):
            raise Conflict("GitHub release draft state is invalid.")
        self.verify_release(release, draft)
        missing = self.inspect_assets(release, not draft)
        if not draft and missing:
            raise RuntimeError("The GitHub asset set is not complete.")

    async def reconcile_github_draft(self) -> None:
        """Create or reconcile the exact draft and upload only absent assets."""
        await self.ensure_tag()
        release = await self.release_json()
        if release is None:
            arguments = [
                "release",
                "create",
                self.tag,
                "--repo",
                REPOSITORY,
                "--draft",
                "--target",
                self.commit,
                "--title",
                self.tag,
                "--notes-file",
                str(self.notes),
            ]
            if "-RC" in self.tag:
                arguments.append("--prerelease")
            await self._gh(*arguments)
            release = await self.release_json()
        if release is None:
            raise RuntimeError("The GitHub draft is not visible yet.")
        draft = release.get("draft")
        if not isinstance(draft, bool):
            raise Conflict("GitHub release draft state is invalid.")
        self.verify_release(release, draft)
        expected = self.expected_assets()
        for name in self.inspect_assets(release, True):
            if not draft:
                raise Conflict(f"The public release is missing asset {name}.")
            await self._gh("release", "upload", self.tag, str(expected[name]), "--repo", REPOSITORY)

    async def publish_github(self, records: list[MavenFile]) -> ReleaseResult:
        """Publish GitHub only after Central and every draft asset are exact."""
        present, missing = await self.central_state()
        if missing or present != len(self.artifacts):
            raise RuntimeError("Maven Central is incomplete immediately before GitHub publication.")
        await self.validate_central_files(records)
        await self.reconcile_github_draft()
        release = await self.release_json()
        if release is None:
            raise RuntimeError("The exact GitHub release is unavailable.")
        if release.get("draft") is True:
            await self.ensure_tag()
            self.verify_assets(release)
            await self._gh("release", "edit", self.tag, "--repo", REPOSITORY, "--draft=false")
        release = await self.release_json()
        if release is None:
            raise RuntimeError("The published GitHub release is unavailable.")
        self.verify_release(release, False)
        self.verify_assets(release)
        url = release.get("html_url")
        if not isinstance(url, str):
            raise RuntimeError("GitHub release URL is unavailable.")
        return ReleaseResult(
            self.digest,
            url,
            f"https://central.sonatype.com/artifact/io.temporal/temporal-sdk/{self.version}",
        )

    async def inspect_maven(self) -> MavenInspection:
        """Return Central visibility and external identity/state for every generation."""
        present, _ = await self.central_state()
        view = await self.sonatype_view()
        generations = []
        for durable in self.value.mavenGenerations:
            repository_id = durable.repositoryId or view.find(self.description(durable.generation))
            repository = view.repository(repository_id) if repository_id else RepositoryView([], [])
            repository_state = (
                cast(str, repository.manual[0].get("state"))
                if repository.manual and repository.manual[0].get("state")
                else "open"
                if repository.profiles
                else "absent"
            )
            portal_id = durable.portalDeploymentId or self._portal_id(repository)
            portal_state = await self.portal_status(portal_id) if portal_id else ""
            generations.append(
                MavenGeneration(
                    generation=durable.generation,
                    repositoryId=repository_id,
                    repositoryState=repository_state,
                    portalDeploymentId=portal_id,
                    portalDeploymentState=portal_state,
                )
            )
        return MavenInspection(present, generations)

    async def publish_release(self) -> ReleaseResult:
        """Reconcile the complete release in Maven-first, GitHub-public-last order."""
        await self.materialize_native_assets()
        root, records = await self.materialize_maven_payload()
        await self.verify_github_preflight()
        present, missing = await self.central_state()
        if not missing and present == len(self.artifacts):
            return await self.publish_github(records)
        repository_id = await self.reconcile_repository(present)
        portal_id = await self.reconcile_portal(repository_id, root, records)
        await self.publish_maven(portal_id)
        return await self.publish_github(records)
