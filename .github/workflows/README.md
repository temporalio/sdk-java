# sdk-java release workflows

## Temporal-backed release automation

This standalone Java application is the opinionated release orchestrator for
`temporalio/sdk-java`. Repository policy, Maven coordinates/endpoints, maintenance module sets,
native platforms, and asset names are fixed in trusted code. Dynamic values are limited to
Temporal Cloud deployment settings, credentials, durable storage, the frozen trusted automation
commit, and one release's immutable identity.

No test or validation procedure may publish Maven coordinates, push a tag, or create a GitHub
release. Tests use local Temporal facilities and mocks only.

## Immutable normal path

1. A push adding exactly one `releases/vX.Y.Z[-RCN]` freezes repository, version/tag, full source
   SHA, release-note path/hash, Maven policy, and `RELEASE_AUTOMATION_REF`. The candidate identity is
   also stored create-if-absent for recovery independent of Temporal.
2. `CandidateWorkflow` schedules the fixed six native builds on release-specific queues. Actions
   compiles each candidate in a separate job with no Temporal, AWS, OIDC, publication, or GitHub
   token. A trusted Ubuntu Activity validates the raw executable and its immutable identity,
   packages it, adopts a checksum-validated existing S3 object, or uploads create-if-absent.
   Artifact bytes never enter Temporal history.
3. The exact six-object manifest becomes part of `ReleaseIdentity`. The candidate records the child
   identity, starts `ReleaseWorkflow` on its release-specific queue, and waits for Temporal to
   acknowledge that start.
4. The release waits indefinitely in `AWAITING_APPROVAL`. Scheduled recovery creates and locks an
   issue showing tag, full SHA, notes digest, manifest digest, release digest, Workflow ID, and Run
   ID. The issue node, creator, and body hash are frozen in an immutable S3 approval-request receipt.
   An active release manager approves with no text by closing it. If closure beats the first binding
   Update, scheduled recovery adopts that exact bot-created issue and delivers approval later. The
   durable Update is authoritative even if the runner fails after submitting it.
5. Publication exposes durable `PREFLIGHT`, `MAVEN`, `GITHUB_DRAFT`, and `PUBLISH_GITHUB` phases.
   Each phase is a separately retryable Activity; Workflow timers own retry/backoff.
6. GitHub publication creates/adopts an exact draft, reconciles six native archives plus
   `SHA256SUMS`, removes a zero-byte interrupted `starter` asset when safe, and makes the release
   public only as the final mutation.

Workflow IDs and all Workflow/Activity Task Queues derive from immutable candidate/release digests,
never Actions run IDs. Publication queues also include the append-only Maven generation, preventing
a stale runner from consuming work authorized for a later generation. New candidates freeze the
current protected `RELEASE_AUTOMATION_REF`; recovery may check out that exact older commit only while
it remains in `RELEASE_AUTOMATION_COMPATIBLE_REFS`. Task Queues are routing, not authorization.
S3 HEAD classifies only an explicit `404`/`NoSuchKey`/`NotFound` service response as absence;
authentication, authorization, throttling, transport, and other service failures remain retryable
Activity failures.

For maintenance branches that predate these workflow files, default-branch scheduled discovery
finds an untagged release-note file, resolves the exact commit that added it, and persists the
candidate before starting Temporal. A separate create-if-absent start receipt is written only after
the returned execution, exact Run ID, memo, Task Queue, and start-event input have been verified.
The child release records that Candidate Run ID and must have the exact parent/root execution.
This lets discovery resume a runner loss between those operations without attaching to a poisoned
same-ID execution or repeatedly opening completed candidates. A
successful maintenance `Continuous Integration` run can also trigger the same path when that branch
has a compatible CI workflow. Scheduled default-branch preflight validates open maintenance
release-note PRs. Source builds run on Java 17; older SHAs receive frozen
releaseVersion/releaseCommit hooks, Nexus plugin 1.3 compatibility, and frozen Java-17 native-image
container definitions when absent from the source SHA.

## Control, retries, and handoff

`Control Temporal-backed release` provides exact-tag/full-SHA `inspect`, `pause`, `resume`,
`handoff-manual`, and `retry-maven-submission` operations.

- Pause cancels an active Activity and waits for acknowledgement. Heartbeats deliver cancellation,
  and `ProcessSupport` terminates the subprocess tree.
- Resume reconciles external state from the durable phase; it does not assume a prior attempt did
  nothing.
- Handoff records actor/tag/SHA/fixed reason/Actions run/Workflow time, reaches `HANDED_OFF`, and
  becomes inert. Discovery skips paused, blocked, handed-off, and not-yet-due retry work.
- Maven generation advance is not authorized by a Temporal Update. A protected control job
  authenticates the actor, performs a fresh exact inspection while the Workflow is durably blocked,
  writes an immutable release/run/generation authorization receipt, and only then submits an Update bound to
  that receipt. Publication rechecks the receipt and active manager membership using publication
  credentials. Any live Portal state vetoes generation advancement.

Activity-capable Workers exit after two minutes if they receive no Activity task. This prevents a
Worker started during durable backoff from holding the shared publication lock for the full job
timeout. GitHub's shared publication concurrency group queues one pending controller without
cancelling an active release. The dispatch-only fallback records a locked request before entering
that queue, so scheduled controllers stop replacing its pending job; interrupted runs are retried.

## Maven reconciliation

External side effects are at-least-once; immutable identity/checksum conflicts are non-retryable.

- A pinned, capability-dropped container runs candidate-controlled Gradle with no host credentials,
  cloud tokens, signing material, or host process namespace. After it exits, trusted code discards
  every candidate-produced signature/checksum, signs the allowlisted payload, adds required MD5 and
  SHA-1 sidecars, and calls Sonatype directly. Source-policy validation and authoritative Sonatype
  reads happen before any intent is written.
- Each submission generation has append-only intent, Sonatype repository-ID, and Publisher Portal
  deployment-ID receipts. Advancing a generation never overwrites earlier evidence.
- Before Sonatype mutation, the exact allowlisted signed Maven payload is stored as one
  content-addressed S3 archive with an immutable release-wide receipt. Every generation downloads
  and validates that archive rather than regenerating timestamp-bearing signatures. The Activity
  recursively enumerates the entire staging repository, rejects every unexpected path, and uploads only missing
  bytes to the receipted repository, then closes that exact repository.
- Closing the compatibility repository transfers it to Publisher Portal. The resulting
  `portal_deployment_id` is persisted and the exact `PENDING`, `VALIDATING`, `VALIDATED`,
  `PUBLISHING`, `PUBLISHED`, or `FAILED` state is reconciled before Central visibility.
- Every visible Central POM must contain exact `io.temporal` group, expected artifact/version, and
  full source SHA in `scm.tag`. Every Central artifact/signature byte must also match the frozen
  payload. The receipted Portal deployment must reach `PUBLISHED` even if its compatibility
  repository disappears. Partial visibility retries; contradictory identity conflicts.

The compatibility service cannot atomically create a repository and write its returned ID to an
external receipt. A runner can still die in that narrow interval. This infeasibility is accepted:
the exact generation description is adopted when visible, while unresolved ambiguity blocks for a
release-manager inspection instead of authorizing another submission from Central absence.

## Two emergency implementations

`temporal-release-emergency-control.yml` is the durable automated emergency controller. It has
fixed `build-artifacts`, `replace-artifacts`, `inspect`, `handoff-and-publish`, and protected
`authorize-maven-retry` actions. The request freezes the candidate's original automation commit,
uses one immutable artifact prefix at a time, validates every object/name/checksum before adoption,
and records stage/time/generation/error on `BLOCKED`. Scheduled recovery builds or publishes from
S3 and records `COMPLETE`; if no Temporal Workflow ever existed, synchronization records
`NO_WORKFLOW` terminally rather than retrying forever.

`prepare-release.yml` is a separate, dispatch-only manual fallback retained through the first
supervised release and postmortem. It intentionally does not import the Temporal Java policy or
Temporal reconciliation script. Its separate shell implementation and 90-day Actions native
  artifacts preserve the prior manual operating model, while its signed Maven payload is frozen in
  durable storage before staging. It adopts an exact Temporal frozen payload when one exists, so a
  handoff after staging never regenerates signed bytes. Its tools are checked out only at the
  protected automation pin, never from candidate or Temporal state. It requires exact tag/SHA,
  release-manager membership, local validation of the fixed signed Maven set, cross-controller
  Sonatype inspection, and a draft-first seven-asset contract. A fixed-body locked GitHub issue
  created by the publication bot binds tag/SHA, protected controller, authenticated actor, and exact
  Actions run; it is never edited into a mutable state store. Recovery always downloads the native
  artifacts from that owning run, so a later rebuild cannot silently replace the selected bytes.
  Rerun attempts adopt already-uploaded, non-expired platform artifacts from that run instead of
  colliding with or overwriting them. A
  locked request issue is created before any publication job enters GitHub's one-pending concurrency
  queue; tag ownership is activated only after the fallback holds the shared lock. Scheduled
  controllers skip all publication while it is open, and interrupted fallback runs retry
  automatically. Existing draft or public assets must match that frozen set.
Normal Temporal publication independently authenticates that issue and blocks while manual
ownership is active.

All publication implementations use the `sdk-java-release-publication` concurrency group, exact
release-manager authorization, releaseVersion/releaseCommit, tag/SHA ownership, and publish-last
GitHub behavior. The lock provides exclusion; the durable ownership/handoff record transfers
control.

## Security boundary

Temporal Cloud data-plane roles do not separate Workflow polling/starting from Update submission.
An unprivileged-key holder can still create or poison executions, so malformed executions are
isolated during discovery. Such state cannot select privileged code or source: discovery requires
the protected automation pin, and publication independently matches the candidate plus Candidate
Workflow start against create-if-absent S3 receipts. An Update cannot independently authorize
publication.

Publication credentials exist only in the protected publication jobs. Before every privileged
Activity, Java and shell checks independently bind the Activity Workflow ID/run ID, exact Candidate
parent Run ID, generation-specific queue, repository, tag, full SHA, candidate/start receipts, notes digest, approved manifest,
approval run/actor/issue/immutable request receipt, protected Worker commit, Maven generation, and
protected retry receipt to the Actions job. Completed stage receipts bind the immutable release
identity, manifest, generation, predecessor, and output but deliberately omit controller run IDs,
so an authenticated handoff can resume them. The final GitHub mutation independently rechecks every
Central byte. It re-reads GitHub evidence and rechecks active team membership. Candidate-controlled
Gradle runs only in the isolated no-secret build container. Signing and Sonatype repository creation
run afterward in trusted pinned scripts; candidate code never receives or shares a process namespace
with those credentials. Native candidate Gradle instead runs in a distinct credential-free Actions
job; only its inert executable and identity metadata cross into the trusted packaging Activity.

## Required setup

Configure:

- `RELEASE_AUTOMATION_REF` as the current reviewed full commit SHA. During controlled rotation,
  list only still-supported older reviewed SHAs in comma-separated
  `RELEASE_AUTOMATION_COMPATIBLE_REFS` until their open releases finish.
- `TEMPORAL_RELEASE_ADDRESS`, `TEMPORAL_RELEASE_NAMESPACE`, and separate unprivileged, approval,
  and publication API keys.
- A private versioned/object-locked `RELEASE_ARTIFACT_BUCKET`, plus separate build and publication
  OIDC roles. The build role may create/read candidate identities and artifact prefixes. The
  `RELEASE_CONTROL_ARTIFACT_ROLE_ARN` role may create/read approval-request receipts and must have
  exact `s3:GetObject` access (which covers HEAD and GET) to `sdk-java/emergency/*.json` for the candidate-state
  Activity (it does not need bucket listing). The publication
  role may read artifacts and create/read immutable ownership, Maven, authorization, and completion
  receipts.
- The existing `temporalio/sdk` team. `RELEASE_APPROVAL_GITHUB_TOKEN` needs organization
  team-membership read, Actions-run read, and issue create/read/lock. The
  `RELEASE_PUBLICATION_GITHUB_TOKEN` needs Actions/issue read, issue create/lock for manual
  ownership, and contents write for tags/releases/assets.
- `JAR_SIGNING_KEY`, `JAR_SIGNING_KEY_ID`, `JAR_SIGNING_KEY_PASSWORD`, and Central Portal user-token
  credentials `RH_USER`/`RH_PASSWORD` only in `release-publication`.

Restrict `release-control` and `release-publication` deployments and OIDC trust to this repository,
the exact workflow, the environment, and `refs/heads/main`. Do not add an expiring timed approval
gate. Protect all release workflows and fixed Gradle hooks with CODEOWNERS. Protect `refs/tags/v*`
with a ruleset forbidding update/deletion after creation, and do not let the publication token
bypass it.

`ReleaseStatus` records phase, stage attempt/start/next retry, last error, blocked time, Sonatype
repository ID, Portal deployment ID, Central URL, draft/final URLs, and authenticated control and
generation-authorization records.

## Verification

```bash
bash -n .github/scripts/manual-release.sh .github/scripts/temporal-release/*.sh
./gradlew -p .github/release-automation spotlessCheck test
```
