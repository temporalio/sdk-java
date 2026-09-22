![Temporal Java SDK](https://raw.githubusercontent.com/temporalio/assets/main/files/w/java.png)

# Temporal Java SDK  [![Build status](https://github.com/temporalio/sdk-java/actions/workflows/ci.yml/badge.svg?event=push)](https://github.com/temporalio/sdk-java/actions/workflows/ci.yml) [![Coverage Status](https://coveralls.io/repos/github/temporalio/sdk-java/badge.svg?branch=main)](https://coveralls.io/github/temporalio/sdk-java?branch=main)

[Temporal](https://github.com/temporalio/temporal) is a Workflow-as-Code platform for building and operating
resilient applications using developer-friendly primitives, instead of constantly fighting your infrastructure.

The Java SDK is the framework for authoring Workflows and Activities in Java. (For other languages, see [Temporal SDKs](https://docs.temporal.io/application-development).)

Java SDK:

- [Java SDK documentation](https://docs.temporal.io/docs/java/introduction)
- [Javadoc API reference](https://www.javadoc.io/doc/io.temporal/temporal-sdk/latest/index.html)
- [Sample applications](https://github.com/temporalio/samples-java#samples-directory)

Temporal:

- [Temporal docs](https://docs.temporal.io/)
- [Install Temporal Server](https://docs.temporal.io/docs/server/quick-install)
- [Temporal CLI](https://docs.temporal.io/cli/)

## Supported Java runtimes

- Java 1.8+
- [GraalVM native-image](docs/AOT-native-image.md)

## Build configuration

[Find the latest release](https://search.maven.org/artifact/io.temporal/temporal-sdk) of the Temporal Java SDK at maven central.

Add *temporal-sdk* as a dependency to your *pom.xml*:

    <dependency>
      <groupId>io.temporal</groupId>
      <artifactId>temporal-sdk</artifactId>
      <version>N.N.N</version>
    </dependency>

or to *build.gradle*:

    compile group: 'io.temporal', name: 'temporal-sdk', version: 'N.N.N'

## Protobuf 3.x vs 4.x

The Temporal Java SDK currently supports `protobuf-java` 3.x and 4.x. To support these, the Temporal Java SDK allows any protobuf library >= 3.25.
Temporal strongly recommends using the latest `protobuf-java` 4.x library unless you absolutely cannot. 
If you cannot use protobuf-java 3.25 >=, you can try `temporal-shaded` which includes a shaded version of the `protobuf-java` library.

## Contributing

We'd love your help in improving the Temporal Java SDK. Please review our [contribution guidelines](CONTRIBUTING.md).

## Development

### Development Environment

- **Java 21+** is required to run Gradle, compile the project, and run all tests locally.
- Some optional tests also require the [Temporal CLI](https://docs.temporal.io/cli#installation).

If you're using Apple Silicon, see the [note on Rosetta](#note-on-rosetta).

### Build

```bash
./gradlew clean build
```

### Code Formatting

Code autoformatting is applied automatically during a full Gradle build. Build the project before submitting a PR.
Code is formatted using the `spotless` plugin with the `google-java-format` tool.

### Commit Messages

Overcommit adds some requirements to your commit messages. We follow the
[Chris Beams](http://chris.beams.io/posts/git-commit/) guide to writing git
commit messages. Read it, follow it, learn it, love it.

### Running features tests in CI

For each PR we run the Java tests from the [features repo](https://github.com/temporalio/features/). This requires
your branch to have tags. Without tags, the features tests in CI will fail with a message like:

```text
> Configure project :sdk-java
fatal: No names found, cannot describe anything.
```

This can be resolved by running `git fetch --tags` on your branch. Make sure your fork has tags copied from
the main repo.

### Testing

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

1. Install the [Temporal CLI](https://docs.temporal.io/cli#installation), which comes with a built-in dev server.
2. Find the flags that the dev server will need to run the tests by grepping for `temporal server` in [.github/workflows/ci.yml](.github/workflows/ci.yml).
3. Start the server:

```bash
temporal server start-dev --YOUR-FLAGS-HERE
```

4. Set the `USE_EXTERNAL_SERVICE` environment variable and run the tests:

```bash
USE_EXTERNAL_SERVICE=true ./gradlew test
```

### Note on Rosetta

Newer Apple Silicon Macs do not ship with Rosetta by default, and the version of `protoc-gen-rpc-java` we use (1.34.1) does not ship Apple Silicon binaries.

Gradle is set to hardcode the download of the x86_64 binaries on macOS, but this depends on Rosetta to function. Make sure Rosetta is installed with:

```bash
/usr/bin/pgrep oahd
```

which should return a PID of the Rosetta process. If it doesn't, you'll need to run:

```bash
softwareupdate --install-rosetta
```

for builds to complete successfully.

## Snapshot release

We also publish snapshot releases during SDK development often under the version `1.x.0-SNAPSHOT` where `x` is the next minor release. This allows users to test out new SDK features before an official SDK release.

To add Sonatype snapshot repository to your *pom.xml*:

    <repositories>
        <repository>
            <id>oss-sonatype</id>
            <name>oss-sonatype</name>
            <url>https://central.sonatype.com/repository/maven-snapshots/</url>
            <snapshots>
                <enabled>true</enabled>
            </snapshots>
        </repository>
    </repositories>

Or to *build.gradle*:

    repositories {
        maven {
            url "https://central.sonatype.com/repository/maven-snapshots/"
        }
      ...
    }

Note: Snapshot releases are not official release and normal backwards compatibility, stability or support
does not apply

## License

Copyright (C) 2025 Temporal Technologies, Inc. All Rights Reserved.

Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.

Modifications copyright (C) 2017 Uber Technologies, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this material except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
