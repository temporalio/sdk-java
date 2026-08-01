# sdk-java GitHub workflows

## Temporal-backed release automation

The Temporal Cloud design, immutable identity, approval path, retry model, security boundary, and
setup are documented in [`../release-automation/README.md`](../release-automation/README.md).

## Independent manual fallback (`prepare-release.yml`)

This dispatch-only path is deliberately independent of the Temporal Java application and its S3
control state. Its shell tools are checked out at the protected automation pin rather than selected
by candidate or Temporal state. It is retained through the first supervised release and
postmortem. It accepts only an exact tag and full 40-character source SHA and has two fixed actions:

- `inspect` reads Central, Sonatype/Publisher Portal, the Git tag, and GitHub release without
  mutation.
- `resume` runs behind the repository-wide publication lock, verifies the active release manager,
  records a fixed-body, bot-created locked GitHub issue as authenticated tag/SHA ownership, reuses
  the native artifacts from that exact owning run with 90-day retention, scans both manual and
  Temporal Sonatype repositories, locally validates the allowlisted signed Maven set before upload,
  rejects any later byte mismatch, and publishes the fully verified draft last.

The fallback deliberately stops instead of guessing when any potentially matching Temporal
repository is still unidentifiable or when a fallback repository is open without a Portal
deployment. The compatibility API cannot make repository creation and receipt recording atomic;
this is an accepted operational limitation requiring release-manager inspection, not permission to
submit again.

`build-native-image.yml` is separately dispatchable with exact SHA, tag, and release-note digest.
It remains reusable for the manual path.

The durable automated emergency controller is
`temporal-release-emergency-control.yml`; its scheduled continuation is
`temporal-release-emergency-resume.yml`. It is a convenience recovery implementation, not the
independent fallback.

Do not use any release workflow for testing. Validation must use local Temporal facilities and
mocks only; do not publish a test coordinate, push a tag, or create a GitHub release.
