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
   its `ReleaseWorkflow` child, and waits until Temporal acknowledges that the child started.
4. The release waits indefinitely in `AWAITING_APPROVAL`.
5. A dispatch of **Approve Temporal-backed release** creates and locks a release-specific issue
   displaying the exact tag, full SHA, notes hash, manifest hash, Workflow/run IDs, and release
   digest. A release manager approves without typing identity fields by inspecting it and clicking
   **Close issue**. The close event has no expiration window. Its issue number, node ID, body hash,
   actor, Actions run, and exact Temporal execution are bound into the approval Update.
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

**Control Temporal-backed release** exposes authenticated, release-specific `pause`, `resume`, and
`handoff-manual` Updates. Each operation requires the exact tag and full SHA and verifies the
triggering actor against `temporalio/sdk-java-release-managers`.

- Pause requests cancellation of an active Activity and waits for cancellation acknowledgement;
  `ProcessSupport` heartbeats and terminates its subprocess when Temporal delivers cancellation.
- Resume continues from the durable stage using reconciliation rather than assuming the previous
  attempt did nothing.
- Handoff records actor, tag, SHA, fixed reason, Actions run, and Workflow time, transitions to
  durable `HANDED_OFF`, and leaves the Workflow open but inert. All automatic discovery skips it.

`prepare-release.yml` is the dispatch-only emergency controller retained through the first
supervised release. It requires an exact tag and 40-character SHA, performs a read-only Temporal
inspection followed by read-only reconciliation of Central, Sonatype, tag, release, and asset
state. Only after that succeeds does it record/adopt `HANDED_OFF`; it then uses the same immutable
S3 manifest and stage scripts. It cannot mix or rebuild artifacts: if the approved six-object
manifest is unavailable, it stops for investigation. `build-native-image.yml` remains callable
only by a reviewed emergency workflow and accepts exact tag/SHA/note-hash inputs; any future
replacement build must first add a single durable replacement manifest and keep Temporal handed
off permanently.

Both normal and emergency publication use the repository-wide `sdk-java-release-publication`
concurrency group, the same release-manager authorization, Maven `releaseVersion`/`releaseCommit`,
the same durable tag ownership key, and the same draft-first GitHub contract. Handoff—not the lock
alone—is the ownership transfer.

## Maven and external reconciliation

External side effects are at-least-once, but immutable conflicts are never retried.

- Before the first Sonatype call, publication create-if-absent writes an exact Maven intent to S3.
  Only the Activity attempt that creates that intent may submit a new deployment.
- A later attempt with an existing intent checks all 17 Central POMs. Partial visibility retries.
  Every visible POM must contain the exact source SHA in `scm.tag`.
- If Central is empty after an ambiguous attempt, publication inspects Sonatype staging
  repositories, adopts at most one repository whose `temporal-sdk` POM contains the exact SHA, and
  closes/releases that repository. It never infers permission to submit again from Central absence.
- A durable tag-level ownership object prevents two digests or SHAs from controlling one tag.
- Existing Git tags, release metadata, and assets are accepted only on exact identity/checksum
  equality. Unexpected state exits with conflict status `42`.
- Approval API unavailability or an in-progress run is retryable. Only a completed contradictory
  approval or confirmed inactive/non-member actor exits `43` as non-retryable.

Retry timing lives in Temporal Activity options. Shell scripts perform one remote-state observation
per attempt; there are no long shell polling loops. Activity heartbeats have a one-minute timeout,
so lost runners are detected promptly. Activity Workers exit after an Activity attempt rather than
sleeping through their full capacity window.

## Recovery and security boundary

Scheduled discovery runs every 15 minutes. It reads pending-platform and release-stage memos and
starts only the release-specific Workers still needed. A runner loss leaves Workflow state and
pending/retrying Activities in Temporal Cloud; a later runner resumes them. `PAUSED` and
`HANDED_OFF` executions are never discovered.

Source and automation use separate checkouts. Java resolves scripts from a JVM property set by the
trusted standalone Gradle build, not from the process working directory, and launches every `.sh`
through explicit `bash`, including Windows build runners. Older maintenance SHAs receive the
reviewed releaseVersion/releaseCommit Gradle hooks from the frozen automation checkout for the
duration of the build, after which their checkout is restored.

Temporal Cloud roles are part of the authorization boundary:

- The unprivileged key may start/read candidate/release Workflows and poll unprivileged queues, but
  **must be denied Workflow Update permission** for `ReleaseWorkflow`.
- The approval key may read exact release state and submit approval/control Updates, but cannot poll
  publication Activities or publish externally.
- The publication key may read the exact execution and poll only privileged publication queues; it
  cannot create approval evidence.

This RBAC separation is mandatory: Workflow code cannot determine which Temporal API key submitted
an Update. Publication additionally retrieves the exact successful issue-event Actions run, the
locked closed issue and its body hash, and rechecks the closing actor's active team membership.

## Required setup

Configure reviewed `RELEASE_AUTOMATION_REF`; Temporal address/namespace and three least-privilege
API keys; a private versioned/object-locked artifact bucket and separate build/publication OIDC
roles; the `sdk-java-release-managers` team; an approval GitHub token limited to team/Actions reads;
and the `release-publication` environment containing GitHub release, signing, and Central
credentials. The approval token also needs issue create/lock plus team/Actions reads. The
publication token needs issue/Actions reads. The environment must not add an expiring second
reviewer gate.

Protect both mutation workflows with branch rules/CODEOWNERS. Keep both enabled through the first
observed release and postmortem, then explicitly decide whether the emergency path remains.

## Verification

Release automation CI performs shell syntax checks and runs only local Temporal test facilities:

```bash
bash -n .github/scripts/temporal-release/*.sh
./gradlew -p .github/release-automation spotlessCheck test
```

Never validate this automation by publishing a test release, pushing a tag, or creating a GitHub
release.
