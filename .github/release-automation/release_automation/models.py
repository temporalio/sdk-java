from __future__ import annotations

import hashlib
import re
from dataclasses import asdict, dataclass, field, is_dataclass
from datetime import datetime
from typing import Any, cast

REPOSITORY = "temporalio/sdk-java"
MAVEN_GROUP = "io.temporal"
MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2"
NATIVE_JAVA_DISTRIBUTION = "graalvm-community"
NATIVE_JAVA_VERSION = "23"
TAG = r"v[0-9]+\.[0-9]+\.[0-9]+(?:-RC[0-9]+)?"
SHA = r"[0-9a-f]{40}"
HASH = r"[0-9a-f]{64}"
RUN_ID = r"[0-9a-fA-F-]{16,64}"
ACTOR = r"[A-Za-z0-9-]{1,39}"
ISSUE_NODE = r"[A-Za-z0-9_=-]{8,128}"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def matches(pattern: str, value: str | None) -> bool:
    return value is not None and re.fullmatch(pattern, value) is not None


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def json_value(value: Any) -> Any:
    """Gson-compatible dictionaries: camel-case fields and omitted nulls."""
    if is_dataclass(value):
        value = asdict(cast(Any, value))
    if isinstance(value, dict):
        return {key: json_value(item) for key, item in value.items() if item is not None}
    if isinstance(value, list):
        return [json_value(item) for item in value]
    return value


CURRENT = "current"
CLASSIC = "classic"
CLASSIC_ALPHA = "classic-alpha"
CLASSIC_ALPHA_LITE = "classic-alpha-lite"
MAVEN_ARTIFACTS = (
    "temporal-aws-lambda",
    "temporal-bom",
    "temporal-envconfig",
    "temporal-kotlin",
    "temporal-opentelemetry",
    "temporal-opentracing",
    "temporal-remote-data-encoder",
    "temporal-sdk",
    "temporal-serviceclient",
    "temporal-shaded",
    "temporal-spring-ai",
    "temporal-spring-boot-autoconfigure",
    "temporal-spring-boot-starter",
    "temporal-test-server",
    "temporal-testing",
    "temporal-workflowcheck",
    "temporal-workflowstreams",
)
CLASSIC_ARTIFACTS = (
    "temporal-bom",
    "temporal-kotlin",
    "temporal-opentracing",
    "temporal-remote-data-encoder",
    "temporal-sdk",
    "temporal-serviceclient",
    "temporal-shaded",
    "temporal-spring-boot-autoconfigure",
    "temporal-spring-boot-starter",
    "temporal-test-server",
    "temporal-testing",
)
CLASSIC_ALPHA_ARTIFACTS = tuple(
    name.replace("autoconfigure", "autoconfigure-alpha").replace("starter", "starter-alpha")
    if name.startswith("temporal-spring-boot-")
    else name
    for name in CLASSIC_ARTIFACTS
)
CLASSIC_ALPHA_LITE_ARTIFACTS = tuple(
    name for name in CLASSIC_ALPHA_ARTIFACTS if name not in {"temporal-bom", "temporal-shaded"}
)
MAVEN_POLICIES = {
    CURRENT: MAVEN_ARTIFACTS,
    CLASSIC: CLASSIC_ARTIFACTS,
    CLASSIC_ALPHA: CLASSIC_ALPHA_ARTIFACTS,
    CLASSIC_ALPHA_LITE: CLASSIC_ALPHA_LITE_ARTIFACTS,
}


@dataclass(frozen=True)
class PlatformSpec:
    id: str
    runner: str
    osFamily: str
    arch: str
    musl: bool = False

    @property
    def artifactLabel(self) -> str:
        return f"{self.osFamily}_{self.arch}{'_musl' if self.musl else ''}"

    @property
    def assetPlatform(self) -> str:
        value = f"macOS{self.id[5:]}" if self.id.startswith("macos-") else self.id
        return value.replace("-", "_")

    @property
    def archiveExtension(self) -> str:
        return ".zip" if self.osFamily == "windows" else ".tar.gz"

    @property
    def binaryName(self) -> str:
        return "temporal-test-server.exe" if self.osFamily == "windows" else "temporal-test-server"

    @property
    def distribution(self) -> str:
        return "" if self.osFamily == "linux" else NATIVE_JAVA_DISTRIBUTION

    @property
    def javaVersion(self) -> str:
        return "" if self.osFamily == "linux" else NATIVE_JAVA_VERSION

    def matrix(self) -> dict[str, Any]:
        return {
            name: getattr(self, name)
            for name in (
                "id",
                "runner",
                "osFamily",
                "arch",
                "musl",
                "artifactLabel",
                "assetPlatform",
                "archiveExtension",
                "binaryName",
                "distribution",
                "javaVersion",
            )
        }


PLATFORMS = (
    PlatformSpec("linux-amd64-musl", "ubuntu-latest", "linux", "amd64", True),
    PlatformSpec("linux-amd64", "ubuntu-latest", "linux", "amd64"),
    PlatformSpec("macos-amd64", "macos-15-intel", "macOS", "amd64"),
    PlatformSpec("macos-arm64", "macos-latest", "macOS", "arm64"),
    PlatformSpec("linux-arm64", "ubuntu-24.04-arm", "linux", "arm64"),
    PlatformSpec("windows-amd64", "windows-latest", "windows", "amd64"),
)
NATIVE_PLATFORMS = tuple(item.id for item in PLATFORMS)


def maven_artifacts(policy: str) -> tuple[str, ...]:
    try:
        return MAVEN_POLICIES[policy]
    except KeyError as error:
        raise ValueError("Unsupported sdk-java Maven release policy.") from error


def maven_policy_for_projects(projects: list[str]) -> str:
    require(len(projects) == len(set(projects)), "sdk-java settings contain duplicate projects.")
    actual = set(projects)
    for policy, artifacts in MAVEN_POLICIES.items():
        if actual == set(artifacts):
            return policy
    raise ValueError("The immutable source does not match a reviewed sdk-java Maven policy.")


def platform_spec(platform: str) -> PlatformSpec:
    try:
        return next(item for item in PLATFORMS if item.id == platform)
    except StopIteration as error:
        raise ValueError("Unknown sdk-java native release platform.") from error


@dataclass
class ArtifactEntry:
    name: str
    sha256: str
    size: int

    def validate(self) -> None:
        require(
            matches(r"[A-Za-z0-9][A-Za-z0-9._-]*", self.name), "Artifact name must be a basename."
        )
        require(matches(HASH, self.sha256), "Artifact hash must be SHA-256.")
        require(self.size > 0, "Artifact size must be positive.")

    def canonical(self) -> str:
        self.validate()
        return f"{self.name}\t{self.sha256}\t{self.size}"


@dataclass
class GithubArtifactReceipt:
    artifactId: int
    workflowRunId: int
    artifactName: str
    githubDigest: str
    createdAt: str
    expiresAt: str
    files: list[ArtifactEntry] = field(default_factory=list)

    def validate(self) -> None:
        require(
            self.artifactId > 0 and self.workflowRunId > 0,
            "GitHub artifact and run IDs must be positive.",
        )
        require(
            matches(r"[A-Za-z0-9][A-Za-z0-9._-]*", self.artifactName),
            "GitHub artifact name is invalid.",
        )
        require(
            matches(r"sha256:[0-9a-f]{64}", self.githubDigest),
            "GitHub artifact digest must be SHA-256.",
        )
        try:
            created = datetime.fromisoformat(self.createdAt.replace("Z", "+00:00"))
            expires = datetime.fromisoformat(self.expiresAt.replace("Z", "+00:00"))
        except (AttributeError, ValueError) as error:
            raise ValueError("GitHub artifact time is invalid.") from error
        require(expires > created, "GitHub artifact expiration must follow creation.")
        require(bool(self.files), "GitHub artifact must contain expected files.")
        for item in self.files:
            item.validate()
        require(
            len({item.name for item in self.files}) == len(self.files),
            "Duplicate GitHub artifact filename.",
        )

    def canonical(self) -> str:
        self.validate()
        head = (
            self.artifactId,
            self.workflowRunId,
            self.artifactName,
            self.githubDigest,
            self.createdAt,
            self.expiresAt,
        )
        return (
            "\n".join(map(str, head))
            + "\n"
            + "".join(f"{x.canonical()}\n" for x in sorted(self.files, key=lambda x: x.name))
        )


@dataclass
class ArtifactManifest:
    artifacts: list[GithubArtifactReceipt] = field(default_factory=list)

    def validate(self) -> None:
        require(bool(self.artifacts), "The artifact manifest must not be empty.")
        for item in self.artifacts:
            item.validate()
        require(
            len({x.artifactName for x in self.artifacts}) == len(self.artifacts),
            "Duplicate artifact name.",
        )

    def canonical(self) -> str:
        self.validate()
        return "".join(
            f"{x.canonical()}\n" for x in sorted(self.artifacts, key=lambda x: x.artifactName)
        )

    def digest(self) -> str:
        return sha256(self.canonical())


@dataclass
class CandidateIdentity:
    tag: str
    commitSha: str
    releaseNotesSha256: str
    trustedAutomationCommit: str
    mavenPolicy: str

    def validate(self) -> None:
        require(matches(TAG, self.tag), "Invalid release tag.")
        require(matches(SHA, self.commitSha), "Commit must be a full SHA.")
        require(matches(HASH, self.releaseNotesSha256), "Release notes hash must be SHA-256.")
        require(
            matches(SHA, self.trustedAutomationCommit),
            "Trusted automation commit must be a full SHA.",
        )
        maven_artifacts(self.mavenPolicy)

    @property
    def version(self) -> str:
        return self.tag[1:]

    @property
    def releaseNotesPath(self) -> str:
        return f"releases/{self.tag}"

    def canonical(self) -> str:
        self.validate()
        return "\n".join(
            (
                REPOSITORY,
                self.version,
                self.tag,
                self.commitSha,
                self.releaseNotesPath,
                self.releaseNotesSha256,
                self.trustedAutomationCommit,
                self.mavenPolicy,
            )
        )

    def digest(self) -> str:
        return sha256(self.canonical())


def native_artifact_name(version: str, platform: str) -> str:
    spec = platform_spec(platform)
    return f"temporal-test-server_{version}_{spec.assetPlatform}{spec.archiveExtension}"


def github_native_artifact_name(candidate: CandidateIdentity, platform: str) -> str:
    candidate.validate()
    platform_spec(platform)
    return f"sdk-java-release-native-{candidate.digest()}-{platform}"


@dataclass
class ReleaseIdentity:
    candidate: CandidateIdentity
    manifest: ArtifactManifest
    manifestSha256: str
    candidateRunId: str

    @classmethod
    def create(
        cls, candidate: CandidateIdentity, manifest: ArtifactManifest, run_id: str
    ) -> ReleaseIdentity:
        return cls(candidate, manifest, manifest.digest(), run_id)

    def validate(self) -> None:
        self.candidate.validate()
        self.manifest.validate()
        require(
            self.candidateRunId == ""
            or matches(
                r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", self.candidateRunId
            ),
            "Candidate Workflow Run ID is invalid.",
        )
        require(
            self.manifest.digest() == self.manifestSha256,
            "Artifact manifest hash does not match its contents.",
        )
        files = {x.files[0].name for x in self.manifest.artifacts if len(x.files) == 1}
        receipts = {x.artifactName for x in self.manifest.artifacts if len(x.files) == 1}
        expected_files = {native_artifact_name(self.candidate.version, p) for p in NATIVE_PLATFORMS}
        expected_receipts = {
            github_native_artifact_name(self.candidate, p) for p in NATIVE_PLATFORMS
        }
        require(
            files == expected_files
            and receipts == expected_receipts
            and len(files) == len(self.manifest.artifacts),
            "Artifact manifest is not the fixed sdk-java platform set.",
        )

    def canonical(self) -> str:
        self.validate()
        return f"{self.candidate.canonical()}\n{self.manifestSha256}\n{self.manifest.canonical()}"

    def digest(self) -> str:
        return sha256(self.canonical())


def github_maven_artifact_name(release: ReleaseIdentity) -> str:
    release.validate()
    return f"sdk-java-release-maven-{release.digest()}"


@dataclass
class ApprovalEvidence:
    releaseDigest: str
    workflowId: str
    runId: str
    githubApprovalRunId: int
    githubActor: str
    githubIssueNumber: int
    githubIssueNodeId: str
    githubIssueBodySha256: str
    trustedWorkerCommit: str

    def validate(self) -> None:
        require(
            matches(HASH, self.releaseDigest)
            and matches(r"sdk-java-release/[0-9a-f]{64}", self.workflowId)
            and matches(RUN_ID, self.runId),
            "Approval execution identity is invalid.",
        )
        require(
            self.githubApprovalRunId > 0 and matches(ACTOR, self.githubActor),
            "GitHub approval run identity is invalid.",
        )
        require(
            self.githubIssueNumber > 0
            and matches(ISSUE_NODE, self.githubIssueNodeId)
            and matches(HASH, self.githubIssueBodySha256),
            "GitHub approval issue identity is invalid.",
        )
        require(matches(SHA, self.trustedWorkerCommit), "Trusted worker commit must be a full SHA.")


@dataclass
class ApprovalRequest:
    releaseDigest: str
    workflowId: str
    runId: str
    githubRunId: int
    githubIssueNumber: int
    githubIssueNodeId: str
    githubIssueBodySha256: str
    githubIssueCreator: str
    trustedWorkerCommit: str

    def validate(self) -> None:
        valid = (
            matches(HASH, self.releaseDigest)
            and matches(r"sdk-java-release/[0-9a-f]{64}", self.workflowId)
            and matches(RUN_ID, self.runId)
        )
        valid = (
            valid
            and self.githubRunId > 0
            and self.githubIssueNumber > 0
            and matches(ISSUE_NODE, self.githubIssueNodeId)
        )
        valid = (
            valid
            and matches(HASH, self.githubIssueBodySha256)
            and matches(ACTOR, self.githubIssueCreator)
            and matches(SHA, self.trustedWorkerCommit)
        )
        require(bool(valid), "Invalid release-specific approval request.")

    def matches(self, evidence: ApprovalEvidence) -> bool:
        self.validate()
        evidence.validate()
        return all(
            getattr(self, name) == getattr(evidence, name)
            for name in (
                "releaseDigest",
                "workflowId",
                "runId",
                "githubIssueNumber",
                "githubIssueNodeId",
                "githubIssueBodySha256",
                "trustedWorkerCommit",
            )
        )

    def same_issue(self, other: ApprovalRequest) -> bool:
        self.validate()
        other.validate()
        fields = (
            "releaseDigest",
            "workflowId",
            "runId",
            "githubIssueNumber",
            "githubIssueNodeId",
            "githubIssueBodySha256",
            "githubIssueCreator",
            "trustedWorkerCommit",
        )
        return all(getattr(self, name) == getattr(other, name) for name in fields)


@dataclass
class MavenGenerationState:
    generation: int
    description: str
    submissionStarted: bool = False
    sonatypeRepositoryId: str | None = None
    portalDeploymentId: str | None = None

    @classmethod
    def create(cls, release_digest: str, generation: int) -> MavenGenerationState:
        require(
            generation >= 0 and matches(HASH, release_digest),
            "Maven generation identity is invalid.",
        )
        return cls(generation, f"sdk-java:{release_digest}:{generation}")

    def validate(self, release_digest: str) -> None:
        require(
            self.generation >= 0
            and self.description == f"sdk-java:{release_digest}:{self.generation}",
            "Maven generation description is invalid.",
        )
        require(
            not self.sonatypeRepositoryId or matches(r"[A-Za-z0-9._-]+", self.sonatypeRepositoryId),
            "Sonatype repository ID is invalid.",
        )
        require(
            not self.portalDeploymentId or matches(RUN_ID, self.portalDeploymentId),
            "Portal deployment ID is invalid.",
        )


@dataclass
class MavenGenerationInspection:
    generation: int
    description: str
    repositoryId: str | None
    repositoryState: str
    portalDeploymentId: str | None
    portalDeploymentState: str

    def validate(self, release_digest: str) -> None:
        state = MavenGenerationState.create(release_digest, self.generation)
        require(
            state.description == self.description,
            "Inspected Maven generation description is invalid.",
        )
        state.sonatypeRepositoryId, state.portalDeploymentId = (
            self.repositoryId,
            self.portalDeploymentId,
        )
        state.validate(release_digest)
        require(
            self.repositoryState in {"absent", "open", "closed", "released"},
            "Inspected Sonatype repository state is invalid.",
        )
        require(
            self.portalDeploymentState
            in {"", "PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED", "FAILED"},
            "Inspected Portal state is invalid.",
        )

    def canonical(self) -> str:
        return "\n".join(
            map(
                str,
                (
                    self.generation,
                    self.description,
                    self.repositoryId or "",
                    self.repositoryState,
                    self.portalDeploymentId or "",
                    self.portalDeploymentState,
                ),
            )
        )


@dataclass
class MavenInspection:
    centralPresent: int
    centralMissing: int
    generations: list[MavenGenerationInspection] = field(default_factory=list)

    def validate(self, release_digest: str) -> None:
        total = self.centralPresent + self.centralMissing
        require(
            self.centralPresent >= 0
            and self.centralMissing >= 0
            and 0 < total <= len(MAVEN_ARTIFACTS),
            "Inspected Maven Central state is invalid.",
        )
        for item in self.generations:
            item.validate(release_digest)
        require(
            len({x.generation for x in self.generations}) == len(self.generations),
            "Inspected Maven generation is duplicated.",
        )

    def canonical(self, release_digest: str) -> str:
        self.validate(release_digest)
        return f"{self.centralPresent}\n{self.centralMissing}\n" + "".join(
            f"{x.canonical()}\n" for x in sorted(self.generations, key=lambda x: x.generation)
        )


@dataclass
class ControlEvidence:
    action: str
    releaseDigest: str
    workflowId: str
    runId: str
    githubRunId: int
    githubActor: str
    tag: str
    commitSha: str
    reason: str
    recordedAtMillis: int = 0
    mavenSubmissionGeneration: int = -1
    authorizationSha256: str | None = None
    mavenInspection: MavenInspection | None = None
    manualMavenRequested: bool = False

    def validate(self) -> None:
        valid = self.action in {"pause", "resume", "handoff-manual", "retry-maven-submission"}
        valid = (
            valid
            and matches(HASH, self.releaseDigest)
            and matches(r"sdk-java-release/[0-9a-f]{64}", self.workflowId)
            and matches(RUN_ID, self.runId)
        )
        valid = (
            valid
            and self.githubRunId > 0
            and matches(ACTOR, self.githubActor)
            and matches(TAG, self.tag)
            and matches(SHA, self.commitSha)
            and bool(self.reason)
        )
        require(bool(valid), "Invalid authenticated release control evidence.")
        if self.action == "retry-maven-submission":
            require(
                self.mavenSubmissionGeneration > 0
                and matches(HASH, self.authorizationSha256)
                and self.mavenInspection is not None,
                "Maven retry control requires an exact authorized inspection.",
            )
            inspection = self.mavenInspection
            assert inspection is not None
            inspection.validate(self.releaseDigest)
            require(
                sha256(inspection.canonical(self.releaseDigest)) == self.authorizationSha256,
                "Maven retry inspection digest differs.",
            )
        require(
            not self.manualMavenRequested or self.action == "handoff-manual",
            "Manual Maven intent is only valid for a manual handoff.",
        )


@dataclass
class OwnershipClaim:
    tag: str
    commitSha: str
    releaseDigest: str | None
    owner: str
    githubActor: str | None = None
    githubRunId: int = 0
    handoffConfirmed: bool = False

    def validate(self) -> None:
        require(matches(TAG, self.tag), "Ownership tag is invalid.")
        require(matches(SHA, self.commitSha), "Ownership commit must be a full SHA.")
        require(self.owner in {"TEMPORAL", "MANUAL"}, "Ownership controller is invalid.")
        require(
            self.owner != "TEMPORAL" or matches(HASH, self.releaseDigest),
            "Temporal ownership requires a release digest.",
        )
        require(
            self.owner != "MANUAL" or bool(self.githubActor) and self.githubRunId > 0,
            "Manual ownership requires its authenticated GitHub run.",
        )
        require(
            self.owner != "MANUAL" or not self.releaseDigest or matches(HASH, self.releaseDigest),
            "Manual ownership release digest is invalid.",
        )
        require(
            not self.handoffConfirmed or bool(self.releaseDigest),
            "A confirmed handoff requires the exact release digest.",
        )


@dataclass
class OwnershipStatus:
    tag: str
    commitSha: str
    releaseDigest: str | None
    owner: str
    githubActor: str | None
    githubRunId: int
    recordedAtMillis: int
    manualMavenState: str
    manualMavenActor: str | None = None
    manualMavenRunId: int = 0

    @classmethod
    def from_claim(cls, claim: OwnershipClaim, now: int) -> OwnershipStatus:
        return cls(
            claim.tag,
            claim.commitSha,
            claim.releaseDigest,
            claim.owner,
            claim.githubActor,
            claim.githubRunId,
            now,
            "NOT_STARTED" if claim.owner == "MANUAL" else "",
        )


@dataclass
class ManualMavenAttempt:
    state: str
    tag: str
    commitSha: str
    releaseDigest: str
    githubActor: str
    githubRunId: int

    def validate(self) -> None:
        valid = (
            self.state in {"STARTED", "COMPLETED"}
            and matches(TAG, self.tag)
            and matches(SHA, self.commitSha)
        )
        valid = (
            valid
            and matches(HASH, self.releaseDigest)
            and matches(ACTOR, self.githubActor)
            and self.githubRunId > 0
        )
        require(bool(valid), "Invalid manual Maven attempt evidence.")


@dataclass
class MavenReceipt:
    mavenCentralUrl: str
    sonatypeRepositoryId: str
    portalDeploymentId: str | None = None


@dataclass
class ReleaseResult:
    releaseDigest: str
    githubReleaseUrl: str
    mavenCentralUrl: str


@dataclass
class CandidateStatus:
    identity: CandidateIdentity | None
    pendingPlatforms: list[str] = field(default_factory=list)
    artifacts: list[GithubArtifactReceipt] = field(default_factory=list)
    releaseIdentity: ReleaseIdentity | None = None


@dataclass
class ReleaseStatus:
    phase: str
    identity: ReleaseIdentity | None = None
    approvalRequest: ApprovalRequest | None = None
    approval: ApprovalEvidence | None = None
    control: ControlEvidence | None = None
    pausedFrom: str | None = None
    handedOffFrom: str | None = None
    lastCompletedStage: str | None = None
    lastError: str | None = None
    blockedAtMillis: int = 0
    mavenCentralUrl: str | None = None
    sonatypeRepositoryId: str | None = None
    portalDeploymentId: str | None = None
    githubDraftUrl: str | None = None
    githubReleaseUrl: str | None = None
    mavenSubmissionGeneration: int = 0
    mavenRetryAuthorization: ControlEvidence | None = None
    mavenPayload: GithubArtifactReceipt | None = None
    mavenGenerations: list[MavenGenerationState] = field(default_factory=list)
    ownership: OwnershipStatus | None = None
    stageAttempt: int = 0
    stageStartedAtMillis: int = 0
    nextRetryAtMillis: int = 0


@dataclass
class PublicationInput:
    release: ReleaseIdentity
    approvalRequest: ApprovalRequest
    approval: ApprovalEvidence
    workflowId: str
    runId: str
    mavenSubmissionGeneration: int = 0
    mavenRetryAuthorization: ControlEvidence | None = None
    mavenPayload: GithubArtifactReceipt | None = None
    mavenGenerations: list[MavenGenerationState] = field(default_factory=list)


@dataclass
class DiscoveryJob:
    role: str
    taskQueue: str
    runner: str = "ubuntu-latest"
    distribution: str = "temurin"
    workflowId: str | None = None
    runId: str | None = None
    tag: str | None = None
    version: str | None = None
    commitSha: str | None = None
    notesSha256: str | None = None
    manifestSha256: str | None = None
    releaseDigest: str | None = None
    candidateDigest: str | None = None
    candidateRunId: str | None = None
    approvalIssueNumber: str | None = None
    approvalIssueNodeId: str | None = None
    approvalIssueBodySha256: str | None = None
    automationCommit: str | None = None
    platform: str | None = None
    javaVersion: str = "17"
    assetPlatform: str | None = None
    archiveExtension: str | None = None
    binaryName: str | None = None


def candidate_workflow_id(candidate: CandidateIdentity) -> str:
    return f"sdk-java-release-candidate/{candidate.digest()}"


def release_workflow_id(release: ReleaseIdentity) -> str:
    return f"sdk-java-release/{release.digest()}"


def candidate_queue_from_digest(digest: str) -> str:
    require(matches(HASH, digest), "Invalid sdk-java release digest.")
    return f"sdk-java-release-candidate-{digest[:32]}-workflow"


def candidate_queue(candidate: CandidateIdentity) -> str:
    return candidate_queue_from_digest(candidate.digest())


def build_queue_from_digest(digest: str, platform: str) -> str:
    require(matches(HASH, digest), "Invalid sdk-java release digest.")
    normalized = platform.lower()
    require(matches(r"[a-z0-9-]+", normalized), "Invalid build platform.")
    return f"sdk-java-release-candidate-{digest[:32]}-build-{normalized}"


def release_queue(release: ReleaseIdentity) -> str:
    return f"sdk-java-release-{release.digest()[:32]}-workflow"


def publication_queue(release: ReleaseIdentity, generation: int = 0) -> str:
    require(generation >= 0, "Maven submission generation cannot be negative.")
    return f"sdk-java-release-{release.digest()[:32]}-publication-g{generation}"


def ownership_queue(tag: str) -> str:
    return f"sdk-java-release-ownership-{sha256(REPOSITORY + chr(10) + tag)[:32]}"


def ownership_workflow_id(tag: str) -> str:
    require(matches(TAG, tag), "Invalid ownership tag.")
    return f"sdk-java-release-ownership/{tag}"
