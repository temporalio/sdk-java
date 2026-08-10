import hashlib
import json
import re
from dataclasses import asdict, dataclass, field, is_dataclass
from typing import Any, NamedTuple, cast

REPOSITORY = "temporalio/sdk-java"
NATIVE_JAVA_DISTRIBUTION = "graalvm-community"
NATIVE_JAVA_VERSION = "23"
TAG = r"v[0-9]+\.[0-9]+\.[0-9]+(?:-RC[0-9]+)?"
SHA = r"[0-9a-f]{40}"
HASH = r"[0-9a-f]{64}"
RUN_ID = r"[0-9a-fA-F-]{16,64}"


def require(condition: bool, message: str) -> None:
    """Reject model state that would weaken an immutable release invariant."""
    if not condition:
        raise ValueError(message)


def matches(pattern: str, value: str | None) -> bool:
    """Return whether a present string fully matches a release identity pattern."""
    return value is not None and re.fullmatch(pattern, value) is not None


def json_value(value: Any) -> Any:
    """Gson-compatible dictionaries: camel-case fields and omitted nulls."""
    if is_dataclass(value):
        value = asdict(cast(Any, value))
    if isinstance(value, dict):
        return {key: json_value(item) for key, item in value.items() if item is not None}
    if isinstance(value, (list, tuple)):
        return [json_value(item) for item in value]
    return value


def digest(*values: Any) -> str:
    """Hash the canonical JSON representation used for durable release identities."""
    data = json.dumps(json_value(values), sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(data.encode()).hexdigest()


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
CLASSIC_ARTIFACTS = tuple(
    name
    for name in MAVEN_ARTIFACTS
    if name
    not in {
        "temporal-aws-lambda",
        "temporal-envconfig",
        "temporal-opentelemetry",
        "temporal-spring-ai",
        "temporal-workflowcheck",
        "temporal-workflowstreams",
    }
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
    "current": MAVEN_ARTIFACTS,
    "classic": CLASSIC_ARTIFACTS,
    "classic-alpha": CLASSIC_ALPHA_ARTIFACTS,
    "classic-alpha-lite": CLASSIC_ALPHA_LITE_ARTIFACTS,
}


class PlatformSpec(NamedTuple):
    platform: str
    runner: str
    assetPlatform: str
    archiveExtension: str
    binaryName: str
    distribution: str
    javaVersion: str


def platform(id: str, runner: str, family: str) -> PlatformSpec:
    """Build one fixed native-platform policy entry and its release naming details."""
    asset = f"macOS{id[5:]}" if id.startswith("macos-") else id
    windows, linux = family == "windows", family == "linux"
    return PlatformSpec(
        id,
        runner,
        asset.replace("-", "_"),
        ".zip" if windows else ".tar.gz",
        "temporal-test-server.exe" if windows else "temporal-test-server",
        "temurin" if linux else NATIVE_JAVA_DISTRIBUTION,
        "17" if linux else NATIVE_JAVA_VERSION,
    )


PLATFORMS = (
    platform("linux-amd64-musl", "ubuntu-latest", "linux"),
    platform("linux-amd64", "ubuntu-latest", "linux"),
    platform("macos-amd64", "macos-15-intel", "macOS"),
    platform("macos-arm64", "macos-latest", "macOS"),
    platform("linux-arm64", "ubuntu-24.04-arm", "linux"),
    platform("windows-amd64", "windows-latest", "windows"),
)
NATIVE_PLATFORMS = tuple(item.platform for item in PLATFORMS)


def maven_artifacts(policy: str) -> tuple[str, ...]:
    """Return the reviewed artifact set for a named sdk-java release policy."""
    try:
        return MAVEN_POLICIES[policy]
    except KeyError as error:
        raise ValueError("Unsupported sdk-java Maven release policy.") from error


def maven_policy_for_projects(projects: list[str]) -> str:
    """Map the immutable Gradle project set to one reviewed Maven policy variant."""
    require(len(projects) == len(set(projects)), "sdk-java settings contain duplicate projects.")
    actual = set(projects)
    for policy, artifacts in MAVEN_POLICIES.items():
        if actual == set(artifacts):
            return policy
    raise ValueError("The immutable source does not match a reviewed sdk-java Maven policy.")


def platform_spec(platform: str) -> PlatformSpec:
    """Resolve an exact native platform or reject unreviewed matrix expansion."""
    try:
        return next(item for item in PLATFORMS if item.platform == platform)
    except StopIteration as error:
        raise ValueError("Unknown sdk-java native release platform.") from error


@dataclass
class GithubArtifactReceipt:
    artifactId: int
    workflowRunId: int
    artifactName: str
    githubDigest: str
    fileName: str


@dataclass
class CandidateIdentity:
    tag: str
    commitSha: str
    mavenPolicy: str
    githubRunId: int

    def validate(self) -> None:
        """Validate every field that contributes to the candidate's stable digest."""
        require(matches(TAG, self.tag), "Invalid release tag.")
        require(matches(SHA, self.commitSha), "Commit must be a full SHA.")
        require(self.githubRunId > 0, "GitHub run ID must be positive.")
        maven_artifacts(self.mavenPolicy)

    @property
    def version(self) -> str:
        """Return the Maven/native version by removing the required tag prefix."""
        return self.tag[1:]

    def digest(self) -> str:
        """Return the repository-scoped immutable candidate identity."""
        self.validate()
        return digest(REPOSITORY, self)


def native_artifact_name(version: str, platform: str) -> str:
    """Return the public native asset filename for one reviewed platform."""
    spec = platform_spec(platform)
    return f"temporal-test-server_{version}_{spec.assetPlatform}{spec.archiveExtension}"


def github_native_artifact_name(candidate: CandidateIdentity, platform: str) -> str:
    """Name the private Actions artifact by candidate digest and platform."""
    platform_spec(platform)
    return f"sdk-java-release-native-{candidate.digest()}-{platform}"


@dataclass
class ReleaseIdentity:
    candidate: CandidateIdentity
    artifacts: list[GithubArtifactReceipt]

    def validate(self) -> None:
        """Require one correctly paired receipt for every fixed native platform."""
        self.candidate.validate()
        actual = {(x.artifactName, x.fileName) for x in self.artifacts}
        expected = {
            (
                github_native_artifact_name(self.candidate, platform),
                native_artifact_name(self.candidate.version, platform),
            )
            for platform in NATIVE_PLATFORMS
        }
        require(
            actual == expected and len(actual) == len(self.artifacts),
            "Artifact manifest is not the fixed sdk-java platform set.",
        )

    def digest(self) -> str:
        """Hash the candidate and order-independent native artifact receipt set."""
        self.validate()
        return digest(self.candidate, sorted(self.artifacts, key=lambda x: x.artifactName))


def github_maven_artifact_name(release: ReleaseIdentity) -> str:
    """Name the private Maven payload artifact with the complete release digest."""
    return f"sdk-java-release-maven-{release.digest()}"


def matches_maven_payload(release: ReleaseIdentity, artifact: GithubArtifactReceipt) -> bool:
    """Check that a receipt names the sole Maven payload for this release."""
    return (
        artifact.artifactName == github_maven_artifact_name(release)
        and artifact.fileName == "maven-payload.tar"
    )


@dataclass
class MavenGeneration:
    generation: int
    submissionStarted: bool = False
    repositoryId: str | None = None
    repositoryState: str = ""
    portalDeploymentId: str | None = None
    portalDeploymentState: str = ""

    def validate(self) -> None:
        """Validate durable and inspected identities for one Maven submission attempt."""
        require(self.generation >= 0, "Maven generation identity is invalid.")
        require(
            not self.repositoryId or matches(r"[A-Za-z0-9._-]+", self.repositoryId),
            "Sonatype repository ID is invalid.",
        )
        require(
            not self.portalDeploymentId or matches(RUN_ID, self.portalDeploymentId),
            "Portal deployment ID is invalid.",
        )
        require(
            self.repositoryState in {"", "absent", "open", "closed", "released"},
            "Inspected Sonatype repository state is invalid.",
        )
        require(
            self.portalDeploymentState
            in {"", "PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED", "FAILED"},
            "Inspected Portal state is invalid.",
        )


@dataclass
class MavenInspection:
    centralPresent: int
    generations: list[MavenGeneration] = field(default_factory=list)

    def validate(self) -> None:
        """Validate an external Maven snapshot before adopting any discovered IDs."""
        require(
            0 <= self.centralPresent <= len(MAVEN_ARTIFACTS),
            "Inspected Maven Central state is invalid.",
        )
        for item in self.generations:
            item.validate()


@dataclass
class ReleaseResult:
    releaseDigest: str
    githubReleaseUrl: str
    mavenCentralUrl: str


@dataclass
class ReleaseStatus:
    phase: str
    pendingPlatforms: list[str] = field(default_factory=list)
    artifacts: list[GithubArtifactReceipt] = field(default_factory=list)
    identity: ReleaseIdentity | None = None
    mavenPayload: GithubArtifactReceipt | None = None
    mavenGenerations: list[MavenGeneration] = field(default_factory=list)


@dataclass
class PublicationInput:
    release: ReleaseIdentity
    mavenPayload: GithubArtifactReceipt | None = None
    mavenGenerations: list[MavenGeneration] = field(default_factory=list)


def candidate_workflow_id(candidate: CandidateIdentity) -> str:
    """Derive the unique Workflow ID from the immutable candidate digest."""
    return f"sdk-java-release-candidate/{candidate.digest()}"


def candidate_queue_from_digest(digest: str) -> str:
    """Derive the candidate-specific Workflow Task Queue from a validated digest."""
    require(matches(HASH, digest), "Invalid sdk-java release digest.")
    return f"sdk-java-release-candidate-{digest[:32]}-workflow"


def candidate_queue(candidate: CandidateIdentity) -> str:
    """Return the Workflow Task Queue owned by one immutable candidate."""
    return candidate_queue_from_digest(candidate.digest())


def publication_queue(release: ReleaseIdentity, generation: int = 0) -> str:
    """Derive the release- and generation-specific privileged Activity Task Queue."""
    require(generation >= 0, "Maven submission generation cannot be negative.")
    return f"sdk-java-release-{release.digest()[:32]}-publication-g{generation}"
