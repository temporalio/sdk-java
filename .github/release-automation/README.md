# Temporal-backed sdk-java release automation

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
2. `CandidateWorkflow` schedules the fixed six native builds on release-specific queues. Each
   Activity adopts a checksum-validated existing S3 object before building; otherwise it uploads
   create-if-absent. Artifact bytes never enter Temporal history.
3. The exact six-object manifest becomes part of `ReleaseIdentity`. The candidate records the child
   identity, starts `ReleaseWorkflow` on its release-specific queue, and waits for Temporal to
   acknowledge that start.
4. The release waits indefinitely in `AWAITING_APPROVAL`. Scheduled recovery creates and locks an
   issue showing tag, full SHA, notes digest, manifest digest, release digest, Workflow ID, and Run
   ID. An active release manager approves with no text by closing it. If closure beats the first
   binding Update, scheduled recovery reads that same locked issue and delivers approval later.
5. Publication exposes durable `PREFLIGHT`, `MAVEN`, `GITHUB_DRAFT`, and `PUBLISH_GITHUB` phases.
   Each phase is a separately retryable Activity; Workflow timers own retry/backoff.
6. GitHub publication creates/adopts an exact draft, reconciles six native archives plus
   `SHA256SUMS`, removes a zero-byte interrupted `starter` asset when safe, and makes the release
   public only as the final mutation.

Workflow IDs and all Workflow/Activity Task Queues derive from immutable candidate/release digests,
never Actions run IDs. Workers check out the automation commit frozen for that release and poll only
its queues. Task Queues are routing, not authorization.

For maintenance branches that predate these workflow files, default-branch scheduled discovery
finds an untagged release-note file, resolves the exact commit that added it, and persists the
candidate before starting Temporal. A separate create-if-absent start receipt lets discovery resume
a runner loss between those operations without repeatedly opening completed candidates. A
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
- Maven generation advance is not authorized by a Temporal Update. A protected publication job
  performs a fresh exact inspection under the shared lock, writes an immutable release/run/generation
  authorization receipt, and only then submits an Update bound to that receipt. Publication checks
  the receipt again using publication credentials.

Activity Workers exit after two minutes if they receive no task. This prevents a Worker started
during durable backoff from holding the shared publication lock for the full job timeout. All
publication concurrency groups use `queue: max`, so a newer scheduled run does not replace a queued
emergency handoff.

## Maven reconciliation

External side effects are at-least-once; immutable identity/checksum conflicts are non-retryable.

- Signing setup, frozen Gradle-hook installation, source-policy validation, and authoritative
  Sonatype reads happen before any intent is written.
- Each submission generation has append-only intent, Sonatype repository-ID, and Publisher Portal
  deployment-ID receipts. Advancing a generation never overwrites earlier evidence.
- The signed Maven payload is generated locally. The Activity checks every expected staged file and
  uploads only missing bytes to the receipted repository, then closes that exact repository.
- Closing the compatibility repository transfers it to Publisher Portal. The resulting
  `portal_deployment_id` is persisted and the exact `PENDING`, `VALIDATING`, `VALIDATED`,
  `PUBLISHING`, `PUBLISHED`, or `FAILED` state is reconciled before Central visibility.
- Every visible Central POM must contain exact `io.temporal` group, expected artifact/version, and
  full source SHA in `scm.tag`. Partial visibility retries; contradictory identity conflicts.

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
supervised release and postmortem. It intentionally does not import the Temporal Java policy,
Temporal reconciliation script, S3 state, or automation pin. Its separate shell implementation and
Actions artifacts preserve the prior manual operating model. The controller is checked out at the
immutable workflow-definition SHA, not loaded from the release candidate. It requires exact tag/SHA,
release-manager membership, exact POM identity, and a draft-first seven-asset contract. A locked
GitHub issue is its durable tag/SHA ownership, frozen asset-manifest digest, and Maven
repository/Portal receipt. Existing draft or public assets must match that frozen manifest byte for
byte. Normal Temporal publication checks the issue before mutation and blocks if manual ownership
is active.

All publication implementations use `sdk-java-release-publication` with `queue: max`, exact
release-manager authorization, releaseVersion/releaseCommit, tag/SHA ownership, and publish-last
GitHub behavior. The lock provides exclusion; the durable ownership/handoff record transfers
control.

## Security boundary

Temporal Cloud data-plane roles do not separate Workflow polling/starting from Update submission.
That limitation is explicitly accepted: an unprivileged-key holder could pause, hand off, or poison
an execution. Malformed executions are isolated during discovery so one cannot abort all recovery.
An Update cannot independently authorize publication.

Publication credentials exist only in the protected publication jobs. Before every privileged
Activity, Java and shell checks independently bind the Activity Workflow ID/run ID, release-specific
queue, repository, tag, full SHA, notes digest, approved manifest, approval run/actor/locked issue,
frozen Worker commit, Maven generation, and protected retry receipt to the Actions job. It re-reads
the completed GitHub run and issue and rechecks active team membership. Stage-specific subprocess
environments do not pass signing/Sonatype credentials to GitHub-only stages.

## Required setup

Configure:

- `RELEASE_AUTOMATION_REF` as a reviewed full commit SHA.
- `TEMPORAL_RELEASE_ADDRESS`, `TEMPORAL_RELEASE_NAMESPACE`, and separate unprivileged, approval,
  and publication API keys.
- A private versioned/object-locked `RELEASE_ARTIFACT_BUCKET`, plus separate build and publication
  OIDC roles. The build role may create/read candidate identities and artifact prefixes. The
  publication role may read artifacts and create/read immutable ownership, Maven, authorization,
  and completion receipts.
- The `sdk-java-release-managers` team. `RELEASE_APPROVAL_GITHUB_TOKEN` needs organization
  team-membership read, Actions-run read, and issue create/read/lock. The
  `RELEASE_PUBLICATION_GITHUB_TOKEN` needs Actions/issue read, issue create/update/lock for manual
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
