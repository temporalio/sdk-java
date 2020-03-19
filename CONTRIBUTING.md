# Developing temporal-java-sdk

This doc is intended for contributors to `temporal-java-sdk` (hopefully that's you!)

**Note:** All contributors also need to fill out the [Uber Contributor License Agreement](http://t.uber.com/cla) before we can merge in any of your changes

## Development Environment

* Java 8.
* Gradle build tool
* Docker

## Licence headers

This project is Open Source Software, and requires a header at the beginning of
all source files. To verify that all files contain the header execute:

```lang=bash
./gradlew licenseCheck
```

To generate licence headers execute

```lang=bash
./gradlew licenseFormat
```

## Commit Messages

Overcommit adds some requirements to your commit messages. At Uber, we follow the
[Chris Beams](http://chris.beams.io/posts/git-commit/) guide to writing git
commit messages. Read it, follow it, learn it, love it.

## Test and Build

Testing and building temporal-java-sdk requires running temporal docker locally, execute:

```bash
curl -O https://raw.githubusercontent.com/temporalio/temporal/master/docker/docker-compose.yml
docker-compose up
```

(If this does not work, see instructions for running the Temporal Server at https://github.com/temporalio/temporal/blob/master/README.md.)

Then run all the tests with:

```bash
./gradlew test
```

Build with:

```bash
./gradlew build
```
