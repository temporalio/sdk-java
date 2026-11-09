# sdk-java GitHub workflows

## Releases

[`release.yml`](release.yml) publishes a release from an exact commit after a
release pull request is merged. Normal releases require two human decisions:

1. Review and merge a pull request that promotes the desired entries from the
   `CHANGELOG.md` `[Unreleased]` section into one new
   `## [X.Y.Z] - YYYY-MM-DD` section.
2. Approve the `release-publication` GitHub environment after the workflow has
   tested the Maven publications and built the native test server executables.
   Approval must be given within 30 days or GitHub automatically fails the
   waiting job.

The protected `publish` job depends on both validation paths, so GitHub does
not request approval until they succeed. GitHub marks the job as **Waiting**
and sends the configured required reviewers a deployment review notification;
open the workflow run and select **Review deployments** to approve or reject
it. Notification delivery outside GitHub depends on each reviewer's settings
or an optional Slack or Microsoft Teams deployment integration.

The workflow creates and verifies the GitHub release before publishing any
Maven artifacts. Draft GitHub releases are the default and leave Maven
unpublished. Release candidates are always bound to the full merge commit SHA.
RC versions use headings such as
`## [1.41.0-RC1] - 2026-10-03` and are published as GitHub prereleases.

Leave the `release-publication` environment variable `DRAFT_RELEASE` unset, or
set it to `1`, to keep the GitHub release as a draft for final inspection. Set
it to `0` to publish the GitHub release and then Maven in the same run. Other
values fail the publication job. To finish a draft later, manually run the
**Release** workflow for the same version with the draft option cleared. The
workflow validates and publishes the existing GitHub draft before publishing
Maven. Publishing the draft in the GitHub UI first is also safe; rerun the
workflow afterward to publish Maven.

An ordinary pull request that only adds entries beneath `[Unreleased]` runs the
candidate check but does not start a release.

To run an existing release candidate manually, open the **Release** workflow,
select **Run workflow** on `main` or a supported backport branch, enter the
version without its leading `v`, and choose whether to hold the GitHub Release
as a draft. The version must already have a versioned `CHANGELOG.md` section.
The workflow finds the exact first-parent commit on the selected branch that
introduced that section and runs it through the same validation, build,
approval, and publication jobs as an automatic release. The manual draft
selection overrides `DRAFT_RELEASE` for that run. Maven is published only after
the GitHub release is public.

Maintainers can check the release scripts locally with
`.github/scripts/test-release.sh`.
The local check requires Bash 4 or newer and GNU `sha256sum`; on macOS these
are available from the Homebrew `bash` and `coreutils` packages.

### One-time repository setup

Configure the existing `release-publication` environment with required
reviewers. GitHub permits up to six users or teams with repository read access,
and one listed reviewer must approve. Preventing self-review is recommended.
Restrict its deployment branch policy to `main` and the supported backport
patterns `releases/*`, `v*.*.x`, `*.*.x`, and `release_*_*_x`. Add an explicit
pattern for each additional slash-separated level used below `releases/`.
Make these secrets available to the workflow, preferably as environment
secrets:

- `JAR_SIGNING_KEY`
- `JAR_SIGNING_KEY_ID`
- `JAR_SIGNING_KEY_PASSWORD`
- `RH_PASSWORD`
- `RH_USER`

The signing key is the base64-encoded secret key ring used by Gradle signing.
The RH credentials must be authorized to publish `io.temporal` through the
Sonatype staging API.

### Recovery

Jobs before GitHub publication are safe to rerun. A draft GitHub release keeps
Maven unpublished. Once the GitHub release is public, a rerun continues after
Maven publication when the `temporal-sdk` POM is on Maven Central and its
`scm.tag` matches the release commit.

If a Sonatype request fails and the release does not become visible on Central,
the workflow stops with an ambiguous-publication error. Inspect Sonatype before
rerunning. Do not authorize another staging generation until the earlier one is
known to be inactive. After that inspection, set the `release-publication`
environment variable `MAVEN_RETRY_COMMIT` to the exact 40-character release
commit and rerun the workflow. Clear the variable after the release; its value
is bound to that commit and cannot authorize another candidate. A published
version or GitHub tag that points to another commit is a permanent error and
must not be replaced.

A run that finds an already-public GitHub release but no matching Central POM
uses the same recovery gate, even when it is a fresh manual run. Promote drafts
through the workflow to keep the first Maven publication attempt unambiguous.
