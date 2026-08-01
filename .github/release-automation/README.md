# Temporal-backed sdk-java release automation

This standalone Gradle application is the opinionated release orchestrator for
`temporalio/sdk-java`. It is excluded from the SDK Maven publication. Repository policy, Maven
coordinates and endpoints, native platforms and asset names are fixed in trusted Java code.
Dynamic configuration is limited to Temporal Cloud, durable artifact storage, credentials, the
frozen trusted Worker commit, and the immutable identity of one release.

## Normal Temporal path

1. A push adding exactly one `releases/vX.Y.Z[-RCN]` file freezes repository, tag/version, full
   source SHA, release-note path/hash, and the current reviewed `RELEASE_AUTOMATION_REF`.
2. One `CandidateWorkflow` schedules the fixed six native builds on digest-derived platform
   queues. Each Activity either returns a checksum-validated candidate object already in S3 or
   builds, archives, and create-if-absent uploads it. Temporal history contains metadata, never
   artifact bytes.
3. The candidate accepts only the exact fixed manifest, freezes it into the release digest, starts
   its `ReleaseWorkflow` child, and waits until Temporal acknowledges that the child started. Before
   starting it, the candidate writes the exact child identity to its memo so scheduled discovery can
   poll the child queue before the child's first Workflow Task has produced a status memo.
4. The release waits indefinitely in `AWAITING_APPROVAL`.
5. Scheduled discovery (or a zero-input dispatch of **Approve Temporal-backed release**) creates or
   adopts and locks a release-specific issue displaying the exact tag, full SHA, notes hash,
   manifest hash, Workflow/run IDs, and release digest. A release manager approves without typing
   identity fields by inspecting it and clicking **Close issue**. The close event has no expiration
   window. Its issue number, node ID, body hash, actor, Actions run, and exact Temporal execution are
   bound into the approval Update.
6. Publication advances through durable `PREFLIGHT`, `MAVEN`, `GITHUB_DRAFT`, and
   `PUBLISH_GITHUB` stages. Each stage is a separately retryable Activity and is visible in the
   `ReleaseStatus` memo.
7. Publication creates or adopts an exact draft, uploads and checksum-verifies the six native
   archives plus `SHA256SUMS`, and makes the release public only as the final mutation.

Workflow IDs and all Workflow/Activity Task Queues derive from the immutable candidate or release
digest, never an Actions run or attempt. Workers always check out the automation SHA frozen into
that release. Task Queue names are routing only; privileged Activities independently validate all
identity and approval fields against the Actions job.

## Pause, resume, and emergency handoff

**Control Temporal-backed release** exposes release-specific `inspect`, `pause`, `resume`,
`handoff-manual`, and ambiguity-only Maven retry operations. Each operation requires the exact tag
and full SHA; mutations also verify the triggering actor against
`temporalio/sdk-java-release-managers`.

- Pause requests cancellation of an active Activity and waits for cancellation acknowledgement;
  `ProcessSupport` heartbeats and terminates its subprocess when Temporal delivers cancellation.
- Resume continues from the durable stage using reconciliation rather than assuming the previous
  attempt did nothing.
- Handoff records actor, tag, SHA, fixed reason, Actions run, and Workflow time, transitions to
  durable `HANDED_OFF`, and leaves the Workflow open but inert. All automatic discovery skips it.

`prepare-release.yml` is the dispatch-only emergency controller retained through the first
supervised release. Its four fixed operations require an exact tag and 40-character SHA and never
depend on a Temporal execution being available:

- `build-artifacts` records an immutable external candidate request. If the normal six-object set is
  already complete it is reused unchanged. Otherwise the request freezes one replacement-attempt
  prefix and the trusted scheduler builds all six objects under that prefix; normal and replacement
  artifacts can never be mixed in one manifest. Runner loss resumes against the same prefix.
- `inspect` requires all six objects, constructs the exact manifest and release digest, and performs
  read-only reconciliation of Central, Sonatype, the tag, release, and assets. It records an
  inspection receipt but performs no publication mutation.
- `handoff-and-publish` is accepted only for the exact previously inspected manifest. It records a
  durable external ownership request with the actor, tag, SHA, fixed reason, time, and Actions run.
  The scheduled emergency controller quiesces Temporal when it is available, claims the tag-level
  ownership object, and reconciles each publication stage with fixed automatic backoff. A Temporal
  outage therefore does not prevent emergency recovery.
- `authorize-maven-retry` is a break-glass choice available only when an intent exists but its exact
  described Sonatype repository cannot be found. It advances one durable submission generation
  after a manager has inspected Sonatype; it is not a general retry knob.

The emergency scheduler records `BLOCKED` on immutable conflicts, `COMPLETE` with exact final URLs,
and an immutable S3 completion receipt. If Temporal was unavailable, later scheduled runs sync the
open Workflow from `HANDED_OFF` to `MANUAL_COMPLETE`. `build-native-image.yml` remains a compatible
reviewed reusable build and its release-only identity inputs are optional for ordinary CI callers.

Both normal and emergency publication use the repository-wide `sdk-java-release-publication`
concurrency group, the same release-manager authorization, Maven `releaseVersion`/`releaseCommit`,
the same durable tag ownership key, and the same draft-first GitHub contract. Handoff—not the lock
alone—is the ownership transfer.

## Maven and external reconciliation

External side effects are at-least-once, but immutable conflicts are never retried.

- Before the first Sonatype call, publication create-if-absent writes an exact Maven intent and a
  unique generation-specific repository description to S3. It creates the empty server-side
  staging repository first, records the returned repository ID, and only then uploads artifacts.
- A later attempt with an existing intent checks every Central POM in the frozen maintenance-policy
  profile. Partial visibility retries. Every visible POM must contain the exact group, artifact,
  version, and source SHA in `scm.tag`.
- If repository creation completed ambiguously, publication adopts at most one repository with the
  exact durable description even before any POM was uploaded. If no such repository exists, the
  Workflow enters `BLOCKED`; only the authenticated generation-advance operation permits another
  creation attempt. It never infers permission to submit again from Central absence.
- A durable tag-level ownership object prevents two digests or SHAs from controlling one tag.
- Existing Git tags, release metadata, and assets are accepted only on exact identity/checksum
  equality. Unexpected state exits with conflict status `42`.
- Approval API unavailability or an in-progress run is retryable. Only a completed contradictory
  approval or confirmed inactive/non-member actor exits `43` as non-retryable.

Normal-path retry timing lives in the Workflow's durable timers with one Activity attempt per
timer. Shell scripts perform one remote-state observation per attempt; there are no long Activity
polling loops. Emergency jobs use a fixed two-minute reconciliation backoff and are rediscovered
from S3 every 15 minutes after runner loss. Activity heartbeats have a one-minute timeout.

## Recovery and security boundary

Scheduled discovery runs every 15 minutes. It reads pending-platform and release-stage memos and
starts only the release-specific Workers still needed. A runner loss leaves Workflow state and
pending/retrying Activities in Temporal Cloud; a later runner resumes them. `PAUSED` and
`HANDED_OFF` executions are never discovered. Before the first Temporal execution exists, a trusted
default-branch watchdog automatically retries failed jobs from the exact release-note push run; it
accepts no alternate ref or identity input.

Source and automation use separate checkouts. Java resolves scripts from a JVM property set by the
trusted standalone Gradle build, not from the process working directory, and launches every `.sh`
through explicit `bash`, including Windows build runners. Older maintenance SHAs receive the
reviewed releaseVersion/releaseCommit Gradle hooks from the frozen automation checkout for the
duration of the build, after which their checkout is restored. Java `ReleasePolicy` is the one
authoritative profile/artifact definition; candidate and preflight scripts ask that code to classify
the immutable source instead of carrying configurable or duplicated module lists.

Temporal Cloud's documented data-plane roles do not separate Workflow polling/starting from Update
submission. This design explicitly accepts that limitation: control Updates are operational
controls and are not an authorization boundary. A holder of the unprivileged key could pause or
hand off a release, so that key must still be narrowly held and rotated, but it cannot authorize
publication. Privileged publication independently retrieves the exact successful issue-event
Actions run, locked closed issue and body hash, rechecks active team membership, and validates the
Workflow/run, tag, SHA, manifest, approval, and trusted Worker commit against the Actions job.

The approval key has no publication credentials. The publication key and external credentials are
available only in protected publication jobs. Stage-specific subprocess environments prevent the
GitHub stages from inheriting signing or Sonatype credentials.

## Required setup

Configure reviewed `RELEASE_AUTOMATION_REF`; Temporal address/namespace and three least-privilege
API keys; a private versioned/object-locked artifact bucket and separate build/publication OIDC
roles; the `sdk-java-release-managers` team; an approval GitHub token limited to team/Actions reads;
and the `release-publication` environment containing GitHub release, signing, and Central
credentials. The approval token also needs issue create/lock plus team/Actions reads. The
publication token needs issue/Actions reads. The environment must not add an expiring second
reviewer gate.

The `release-control` and `release-publication` environments must restrict deployments to `main`
and must not add an expiring reviewer timer. OIDC trust policies must likewise bind this repository,
the exact workflow, environment, and default-branch ref; workflow checks alone do not protect a
secret from a selectable dispatch ref. Protect every release workflow with CODEOWNERS. Keep both
paths enabled through the first observed release and postmortem, then explicitly decide whether the
emergency path remains.

`ReleaseStatus` records the current phase, durable stage attempt, start and next-retry times, last
error, Sonatype repository ID, Central URL, draft URL, final URL, and authenticated control record.
Non-retryable failures enter `BLOCKED` rather than closing the Workflow, so inspection, resume, and
handoff remain available.

## Verification

Release automation CI performs shell syntax checks and runs only local Temporal test facilities:

```bash
bash -n .github/scripts/temporal-release/*.sh
./gradlew -p .github/release-automation spotlessCheck test
```

Never validate this automation by publishing a test release, pushing a tag, or creating a GitHub
release.
