# Developing sdk-java

This doc is intended for contributors to `sdk-java` (hopefully that's you!)

**Note:** All contributors also need to fill out the
[Temporal Contributor License Agreement](https://gist.github.com/samarabbas/7dcd41eb1d847e12263cc961ccfdb197)
before we can merge in any of your changes

## Development Environment

- **Java 21+** is required to run Gradle, compile the project, and run all tests locally.
- Some optional tests also require the [Temporal CLI](https://docs.temporal.io/cli#installation).

If you're using Apple Silicon, see the [note on Rosetta](#note-on-rosetta).


## Build

```
./gradlew clean build
```

## Code Formatting

Code autoformatting is applied automatically during a full gradle build. Build the project before submitting a PR.
Code is formatted using `spotless` plugin with `google-java-format` tool.

## Commit Messages

Overcommit adds some requirements to your commit messages. We follow the
[Chris Beams](http://chris.beams.io/posts/git-commit/) guide to writing git
commit messages. Read it, follow it, learn it, love it.

## Running features tests in CI

For each PR we run the java tests from the [features repo](https://github.com/temporalio/features/). This requires
your branch to have tags. Without tags, the features tests in CI will fail with a message like

```
> Configure project :sdk-java
fatal: No names found, cannot describe anything.
```

This can be done resolved by running `git fetch --tags` on your branch. Note, make sure your fork has tags copied from
the main repo.

## Testing

Run tests:

```bash
./gradlew test
```

Run a single test or group of tests:

```bash
./gradlew :temporal-sdk:test --offline --tests "io.temporal.activity.ActivityPauseTest"
./gradlew :temporal-sdk:test --offline --tests "io.temporal.workflow.*"
```

By default, integration tests run against the built-in time-skipping test server. Some tests require features that the built-in server doesn't support; those tests will be skipped. To run the skipped tests:

1. Install the [temporal CLI](https://docs.temporal.io/cli#installation), which comes with a built-in dev server.
2. Find the flags that the dev server will need to run the tests by grepping for `temporal server` in [./github/workflows/ci.yml](./github/workflows/ci.yml).
3. Start the server: 
```bash
temporal server start-dev --YOUR-FLAGS-HERE
```
4. Set the `USE_EXTERNAL_SERVICE` environment variable and run the tests: 
```bash
USE_EXTERNAL_SERVICE=true ./gradlew test
```

## Note on Rosetta

Newer Apple Silicon macs do not ship with Rosetta by default, and the version of `protoc-gen-rpc-java` we use (1.34.1) does not ship Apple Silicon binaries.

So Gradle is set to hardcode the download of the x86_64 binaries on MacOS, but this depends on Rosetta to function. Make sure Rosetta is installed with

```bash
/usr/bin/pgrep oahd
```

which should return a PID of the Rosetta process. If it doesn't, you'll need to run

```bash
softwareupdate --install-rosetta
```

for builds to complete successfully.
