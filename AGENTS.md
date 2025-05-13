# Layout

The main directory of the repository contains the following folders:
- `temporal-sdk`: This folder contains the SDK code.
- `temporal-testing`: This folder contains the code for helping users test their workflows and activities.
- `temporal-test-server`: This folder contains the code for the time skipping test server. This is an alternate implementation of the Temporal server that supports time skipping for fast testing.
- `temporal-shaded`: This folder contains the code for the shaded Temporal SDK. This is a version of the SDK that is shaded to avoid dependency conflicts with other libraries.
- `temporal-serviceclient`: This folder contains the code for the Temporal service client. This is a client that can be used to communicate with the Temporal server.
- `temporal-sprintg-boot-autoconfigure`: This folder contains the code for the Spring Boot autoconfiguration. This is a library that can be used to automatically configure the Temporal SDK in a Spring Boot application.

# Guide

Generally we should not make any change to the signature of the public API. Classes or interfaces that in an `internal` folder are not considered public API.

# Language

The SDK should be written in Java using Java 8.

# Testing

To format code run:

```bash
./gradlew --offline spotlessApply   
```

To test the SDK run:

```bash
./gradlew :temporal-sdk:test --offline --tests "io.temporal.workflow.*"
```

running all tests can take a long time

To test a new test you added run

```bash
./gradlew :temporal-sdk:test --offline --tests "$TEST"
```

where $TEST is the name of the test including the package.