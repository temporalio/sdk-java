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

The workflow then releases the signed Java artifacts through Sonatype, verifies
the `temporal-sdk` POM and its exact commit on Maven Central, and publishes the
GitHub release and tag. Release candidates are always bound to the full merge
commit SHA. RC versions use headings such as
`## [1.41.0-RC1] - 2026-10-03` and are published as GitHub prereleases.

Set the `release-publication` environment variable `DRAFT_RELEASE` to `1` to
leave the GitHub release as a draft for final inspection. The Maven artifacts
are still published to Maven Central and cannot be recalled. Clear the variable
or set it to `0` for the normal public GitHub release; other values fail the
publication job.

An ordinary pull request that only adds entries beneath `[Unreleased]` runs the
candidate check but does not start a release.

Maintainers can check the release scripts locally with
`.github/scripts/test-release.sh`.

### One-time repository setup

Configure the existing `release-publication` environment with required
reviewers. GitHub permits up to six users or teams with repository read access,
and one listed reviewer must approve. Preventing self-review is recommended.
Keep its deployment branch policy restricted to `main` and make these secrets
available to the workflow, preferably as environment secrets:

- `JAR_SIGNING_KEY`
- `JAR_SIGNING_KEY_ID`
- `JAR_SIGNING_KEY_PASSWORD`
- `RH_PASSWORD`
- `RH_USER`

The signing key is the base64-encoded secret key ring used by Gradle signing.
The RH credentials must be authorized to publish `io.temporal` through the
Sonatype staging API.

### Recovery

Jobs before Maven publication are safe to rerun. A rerun also continues after
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
