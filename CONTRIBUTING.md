# Developing sdk-java

This doc is intended for contributors to `sdk-java` (hopefully that's you!)

**Note:** All contributors also need to fill out the 
[Temporal Contributor License Agreement](https://gist.github.com/samarabbas/7dcd41eb1d847e12263cc961ccfdb197) 
before we can merge in any of your changes

## Development Environment

* Java 11
* Docker

## Build

```
./gradlew clean build
```

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

## Code Formatting

Code in this project is formatted using `spotless` gradle plugin with `google-java-format` tool.
Code autoformatting is applied automatically during a full gradle build.
Or you can run `./gradlew spotlessApply` manually to check and apply formatting.

You can use template git pre-commit hook in `./pre-commit` that ensures that the code is formatted
before each commit. See comments in `./pre-commit` for installation info.

## Commit Messages

Overcommit adds some requirements to your commit messages. We follow the
[Chris Beams](http://chris.beams.io/posts/git-commit/) guide to writing git
commit messages. Read it, follow it, learn it, love it.

## Test and Build

Testing and building `sdk-java` requires running temporal docker locally, execute:

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
