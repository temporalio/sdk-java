# sdk-java GitHub workflows

## Release process

[`prepare-release.yml`](prepare-release.yml) turns the release-notes pull
request into a release without requiring an operator to type a tag or race a
workflow timeout.

The workflow starts when a protected release branch receives exactly one new
file named `releases/vX.Y.Z` or `releases/vX.Y.Z-RCN`. It derives the release
version from that filename, pins every build to the triggering commit, builds
and packages all native test-server executables, and then waits at the
protected `release` environment.

Approving that environment is the only manual release action. After approval,
the workflow:

1. publishes signed Java artifacts to Maven Central;
2. asks Sonatype to close and release the staging repository, with bounded
   retries while Sonatype changes state;
3. waits until the release is visible from Maven Central;
4. creates a correctly tagged draft GitHub release at the same immutable
   commit;
5. uploads and verifies all native archives and their SHA-256 manifest; and
6. publishes the GitHub release.

The GitHub release remains a draft until every asset is present and Maven
Central is available. Re-running a failed job is safe after an irreversible
Maven publication: every release POM records the exact source commit in its
SCM tag, and the workflow resumes only after Central exposes that same commit.

### One-time repository setup

Before this workflow is merged, a repository administrator must create a
GitHub environment named `release` with:

- the Java SDK release maintainers as required reviewers;
- self-approval disabled;
- administrator bypass disabled;
- deployment branches restricted to `main` and the supported maintenance
  branches (`releases/*` plus any still-supported legacy `vX.Y.x`, `X.Y.x`, or
  `release_X_Y_x` branches);
- a secret named `RELEASE_ENVIRONMENT_CONFIGURED` whose value is `true`; and
- the following existing release secrets moved or copied into the
  environment:
  - `JAR_SIGNING_KEY`
  - `JAR_SIGNING_KEY_ID`
  - `JAR_SIGNING_KEY_PASSWORD`
  - `RH_PASSWORD`
  - `RH_USER`

Set the repository's Actions artifact retention to 90 days. The workflow
requests that retention for its release archives, so they outlive GitHub's
30-day maximum wait for an environment approval.

Create an active tag ruleset targeting `v*` that restricts tag updates and
deletions, with no bypass actors. Leave tag creation unrestricted: the
approved workflow creates the release tag at the validated commit immediately
before publishing, while the ruleset prevents it from being moved afterward.

Add the `Release automation` CI job as a required status check on `main` and
every supported maintenance-branch ruleset.

The sentinel secret makes the workflow fail before any publication when the
environment has not been configured. Keep it at environment scope rather than
repository scope. The sentinel cannot inspect GitHub's protection settings, so
verify the required reviewers, branch restrictions, bypass setting, artifact
retention, and tag ruleset before merging the workflow.

### Making a release

1. Add one nonempty release-notes file under `releases/`. Its filename is the
   desired tag, including the leading `v`.
2. Open, review, and merge the release-notes pull request into `main` or the
   appropriate maintenance release branch.
3. Review the candidate jobs and approve the `release` environment when the
   release is ready.

There is no dispatch form, manually edited tag, Sonatype UI action, or
short-lived approval window. If a transient service failure exhausts its
bounded retries, use **Re-run failed jobs** on the same workflow run.

### Safe validation

Do not test the publication path against the upstream repository. The release
metadata, retry, and GitHub draft helpers have isolated tests that do not
contact GitHub or Sonatype:

```bash
.github/scripts/release/test-release-scripts.sh
```

The tests also run in the `Release automation` CI job.
