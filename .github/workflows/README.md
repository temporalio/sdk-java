# sdk-java Github Workflows

## Native Testserver Images (native-testserver-images.yml)

This is a [manually triggered](https://docs.github.com/en/actions/managing-workflow-runs/manually-running-a-workflow) workflow that uses the gradle build files already present in the sdk-java repository to build native executables for Windows, MacOS, and Linux. Currently only the amd64 architecture is supported. We plan to add macOS/aarch64 native builds when builders for that platform become available.  This workflow takes two parameters:
1. `release` - a git tag in the form of a 'v' character followed by a semantic version, e.g. v1.2.3. The presence of this parameter instructs the workflow to upload generated artifacts to the github release identified by that version. By default the value is blank and thus the binaries are built but not attached to any release. If the release specified does not exist this workflow will _not_ create it but rather will fail.
2. `clobber` - a boolean indicating whether or not the release artifact upload should overwrite any existing artifacts attached to the specified release with the same artifact name. If the `release` parameter is blank, this parameter is not used.

### Testing

To test execution of this workflow, first create your own fork of the temporalio/sdk-java repository. In your fork, edit the native-testserver-images.yml (and related files, as necessary) in a branch and push the branch to Github. The Github "Actions" tab in the web UI affords the ability to invoke the workflow on master or on any other branch (you probably want to specify the branch that contains your updates) and pass the parameters described above. If you want to test out the release artifact upload, first create a release (draft release is ok) _of your forked repository_ and specify that release tag in the `release` parameter of the workflow invocation.

Workflows can also be invoked from the `gh` cli. To invoke this workflow and watch its progress

```.sh
$ gh workflow run \
  --ref $GITHUB_BRANCH \
  --field clobber=true \
  --field release=v0.0.1 \
  --repo $GITHUB_USER/sdk-java \
  native-testserver-images.yml \
$ gh run list --workflow=native-testserver-images.yml --repo $GITHUB_USER/sdk-java
$ # Note ID of your workflow run in the output of the command above
$ gh run watch --repo $GITHUB_USER/sdk-java <ID>
```
