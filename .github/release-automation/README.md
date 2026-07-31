# Temporal-backed sdk-java release automation

This directory contains an opinionated release orchestrator for
`temporalio/sdk-java`. It is a standalone Gradle build so it is never included
in the SDK's Maven publication set. It uses the Java SDK from this source tree
through a composite build.

The automation is intentionally not a release framework. The repository,
Maven coordinates, Central endpoint, release-note convention, six native
platforms, asset names, GitHub workflow paths, and release-manager team are
fixed in trusted code. Dynamic values are limited to the Temporal Cloud
deployment, durable artifact-store deployment, credentials, the trusted Worker
commit, and immutable per-release identity.

## Lifecycle

1. A push that adds exactly one `releases/vX.Y.Z[-RCN]` file freezes a candidate
   identity: repository, version/tag, full source SHA, release-note path, and
   release-note SHA-256.
2. The starter creates one `CandidateWorkflow`. Its Workflow ID and Workflow
   and Activity Task Queues are derived from the candidate digest, never an
   Actions run or attempt.
3. Six platform Activities build native test-server archives from the immutable
   source SHA. They upload bytes to the versioned external artifact bucket and
   return only names, sizes, storage keys, and SHA-256 values to Temporal.
4. The candidate Workflow accepts exactly the fixed six-platform manifest,
   freezes a release identity containing that manifest, and starts a
   `ReleaseWorkflow` whose ID and queues derive from the final release digest.
5. The release Workflow waits without a timeout. A release manager clicks the
   no-input **Run workflow** button for `Approve Temporal-backed release`. The
   workflow fails unless exactly one release is pending and the actor has active
   membership in `temporalio/sdk-java-release-managers`. It submits an Update
   addressed to the exact Workflow ID and Run ID.
6. Only after the Update is accepted can the publication discovery job create a
   job in the `release-publication` environment. That job receives publication
   credentials and polls only the release-specific publication queue.
7. The privileged Activity independently compares its Activity execution,
   Workflow/Run IDs, tag, source SHA, note hash, manifest hash, release digest,
   approval run, actor, trusted Worker commit, and Task Queue with the values
   expected by that Actions job. It also retrieves the completed approval run
   from GitHub and rechecks team membership.

The scheduled resume workflows are part of the design, not a fallback requiring
operator intervention. Every 15 minutes they discover open Temporal executions
and launch short-lived Workers for one exact release queue. A lost runner leaves
durable Workflow state and pending/retrying Activities in Temporal Cloud; the
next scheduled run resumes them. Approval is an event, not a pending Actions job,
so it does not expire and has no approval window.

## Security boundary

Candidate source and automation source are separate checkouts. Workers and shell
entrypoints always come from the full commit in `RELEASE_AUTOMATION_REF`; build
and publication commands run against a second checkout at the immutable release
SHA. Updating the trusted pin is a security-sensitive, reviewed operation.

Unprivileged build Workers have no Maven or GitHub publication credentials.
Approval has a Temporal credential that can submit the Update and a GitHub token
that can only inspect Actions/team membership. Publication uses a separate
Temporal credential, GitHub App/PAT credential, signing/Central credentials, and
artifact-store role in the `release-publication` environment. Temporal API keys
should be scoped to this namespace and role as narrowly as Temporal Cloud
permits.

Task Queue names provide deterministic routing and runner isolation; they are
not authorization. `PublicationGuard` and `reconcile-publication.sh` enforce the
privileged authorization boundary again at Activity execution time.

Temporal history contains identities, hashes, storage keys, status, and approval
metadata. It never contains credentials or artifact bytes.

## Retry and reconciliation policy

Retry timing lives in Workflow Activity options. Shell scripts make one
observation of timing-dependent remote state per Activity attempt; they do not
contain long propagation polling loops. Temporal supplies durable exponential
backoff and a later transient Worker supplies execution capacity.

External effects are at-least-once:

- S3 uploads use create-if-absent and reconcile the stored size and checksum.
- Maven Central is checked at the fixed set of all 17 published sdk-java POM
  coordinates. Existing coordinates are accepted only when every visible POM's
  `scm.tag` is the exact release SHA. A durable submission marker separates
  publication from later single-check propagation attempts. Partial propagation
  is retried; coordinate/SHA conflicts are non-retryable.
- The Git tag must be absent or point directly at the exact source commit.
- GitHub release metadata must exactly match the tag, notes, and prerelease
  state. Existing assets are downloaded and checksum-verified; unexpected or
  conflicting assets are non-retryable. Missing assets are uploaded and the
  final asset set is compared exactly.

Exit status `42` denotes an immutable identity/checksum conflict and `43`
denotes invalid approval evidence. Activities convert both to non-retryable
Temporal failures. Network errors and temporary absence remain retryable.

## Required repository setup

Before enabling the workflows, configure:

- `RELEASE_AUTOMATION_REF`: a reviewed full 40-character commit containing the
  trusted Worker and scripts. Protect changes to release automation with
  CODEOWNERS/branch rules.
- `TEMPORAL_RELEASE_ADDRESS` and `TEMPORAL_RELEASE_NAMESPACE`.
- `TEMPORAL_RELEASE_UNPRIVILEGED_API_KEY`,
  `TEMPORAL_RELEASE_APPROVAL_API_KEY`, and
  `TEMPORAL_RELEASE_PUBLICATION_API_KEY` with separate least-privilege
  identities.
- `RELEASE_ARTIFACT_BUCKET`: a private, versioned/object-locked bucket in
  `us-west-2`.
- `RELEASE_ARTIFACT_ROLE_ARN`: OIDC role limited to candidate object creation
  and exact-object reads/metadata.
- `RELEASE_PUBLICATION_ARTIFACT_ROLE_ARN`: OIDC role limited to reading approved
  release objects and writing release state markers.
- The `sdk-java-release-managers` GitHub team.
- `RELEASE_APPROVAL_GITHUB_TOKEN`: preferably a GitHub App token limited to
  reading that team and Actions run metadata.
- A `release-publication` environment containing
  `RELEASE_PUBLICATION_GITHUB_TOKEN`, `JAR_SIGNING_KEY`,
  `JAR_SIGNING_KEY_ID`, `JAR_SIGNING_KEY_PASSWORD`, `RH_USER`, and
  `RH_PASSWORD`. Restrict the environment to the default/protected release
  branches. Its GitHub token needs release/tag writes plus approval-run and team
  reads.

The environment must not add a second required-reviewer gate: the no-input
approval Workflow is the human gate, and removing a pending environment approval
window is what permits indefinite waiting and automatic post-approval recovery.

The existing `prepare-release.yml` remains unchanged during evaluation. Disable
its manual publication path as part of production cutover so there is only one
authorized release path.

## Local verification

Tests use `TestWorkflowEnvironment` and mocks only:

```bash
./gradlew -p .github/release-automation spotlessApply test
```

Never exercise publication scripts as a release test. Validate them with shell
syntax checks and mocked command fixtures only.
