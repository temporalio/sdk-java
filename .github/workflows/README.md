# sdk-java Github Workflows

## Temporal-backed release automation

The separate Temporal Cloud-backed design, security boundary, setup, approval
path, retries, and exact-state reconciliation are documented in
[`../release-automation/README.md`](../release-automation/README.md). It remains
separate from the legacy workflow below until production cutover is explicitly
completed.

## Prepare Release (prepare-release.yml)

This is the dispatch-only emergency controller for the exact tag and full source SHA. Its fixed
operations durably build missing native artifacts, perform a read-only inspection, authorize a
previously inspected manifest for handoff/publication, or resolve an ambiguous Maven intent.

This workflow requires five secrets:

- `JAR_SIGNING_KEY`
- `JAR_SIGNING_KEY_ID`
- `JAR_SIGNING_KEY_PASSWORD`
- `RH_PASSWORD`
- `RH_USER`

The trusted scheduled emergency workflow resumes build or publication after runner loss. Publication
uses exact Central/POM reconciliation and creates a GitHub draft, verifies all seven assets, then
makes it public as the final mutation.

### Testing

Do not use this workflow for testing. `handoff-and-publish` authorizes real Maven, tag, asset, and
GitHub release mutations after the separate `inspect` operation. No release action is safe to test
against production credentials.

Workflows can also be invoked from the `gh` cli. To invoke this workflow and watch its progress

```.sh
$ gh workflow run --repo temporalio/sdk-java --ref main \
    --field action=inspect --field tag=v1.2.3 \
    --field commit_sha=0123456789abcdef0123456789abcdef01234567 prepare-release.yml
$ gh run list --workflow prepare-release.yml --repo temporalio/sdk-java
$ # Note ID of your workflow run in the output of the command above
$ gh run watch --repo temporalio/sdk-java <ID>
```
