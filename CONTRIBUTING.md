# Developing temporal-java-sdk

This doc is intended for contributors to `temporal-java-sdk` (hopefully that's you!)

**Note:** All contributors also need to fill out the 
[Temporal Contributor License Agreement](https://gist.github.com/samarabbas/7dcd41eb1d847e12263cc961ccfdb197) 
before we can merge in any of your changes

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
