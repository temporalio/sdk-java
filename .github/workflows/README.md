# sdk-java Github Workflows

## Prepare Release (prepare-release.yml)

This is a [manually triggered](https://docs.github.com/en/actions/managing-workflow-runs/manually-running-a-workflow) workflow that uses the gradle build files already present in the sdk-java repository to prepare release artifacts for publication.  This workflow takes a tag string and a git ref to use in peparing a release.  There is an expectation that if preparing a given release (e.g. v1.2.3) then there exists a file in the repository, releases/<tag> (i.e. releases/v1.2.3) containing release notes. This file must be present on the ref passed to the workflow invocation.

This workflow requires five secrets:

- `JAR_SIGNING_KEY`
- `JAR_SIGNING_KEY_ID`
- `JAR_SIGNING_KEY_PASSWORD`
- `RH_PASSWORD`
- `RH_USER`

 The results of running this workflow are

 - A *DRAFT* Github release will be created
 - Signed jars *STAGED* to the Sonatype Nexus artifact repository

 To complete the release, the releaser should

 - Validate and publish the Github release
 - Approve and publish the jars via the Sonatype UI

### Testing

This workflow does not publish release artifacts in a way that is externally
visible and thus it is safe to execute at any time as long as the resulting
draft release and unpublished jars are cleaned up.

Workflows can also be invoked from the `gh` cli. To invoke this workflow and watch its progress

```.sh
$ gh workflow run --repo temporalio/sdk-java --field tag=v1.2.3 prepare-release.yml
$ gh run list --workflow prepare-release.yml --repo temporalio/sdk-java
$ # Note ID of your workflow run in the output of the command above
$ gh run watch --repo temporalio/sdk-java <ID>
```
