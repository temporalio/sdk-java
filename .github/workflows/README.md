# sdk-java GitHub workflows

## Temporal-backed release automation

The Temporal Cloud design, immutable identity, approval path, retry model, security boundary, and
setup are documented in [`../release-automation/README.md`](../release-automation/README.md).

## Independent manual fallback (`prepare-release.yml`)

This dispatch-only path is deliberately independent of the Temporal Java application, its trusted
automation pin, and its S3 control state. It is retained through the first supervised release and
postmortem. It accepts only an exact tag and full 40-character source SHA and has two fixed actions:

- `inspect` reads Central, Sonatype/Publisher Portal, the Git tag, and GitHub release without
  mutation.
- `resume` runs behind the repository-wide publication lock, verifies the active release manager,
  records a locked GitHub issue as durable tag/SHA ownership, builds the fixed native set, resumes
  from exact external state, freezes the seven-asset manifest in that ownership record, rejects any
  later byte mismatch, and publishes the fully verified draft last.

The fallback deliberately stops instead of guessing when a staging repository exists without a
durable repository/Portal receipt. The compatibility API cannot make repository creation and
receipt recording atomic; this is an accepted operational limitation requiring release-manager
inspection, not permission to submit again.

`build-native-image.yml` is separately dispatchable with exact SHA, tag, and release-note digest.
It remains reusable for the manual path.

The durable automated emergency controller is
`temporal-release-emergency-control.yml`; its scheduled continuation is
`temporal-release-emergency-resume.yml`. It is a convenience recovery implementation, not the
independent fallback.

Do not use any release workflow for testing. Validation must use local Temporal facilities and
mocks only; do not publish a test coordinate, push a tag, or create a GitHub release.
