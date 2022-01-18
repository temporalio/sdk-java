# Temporal Workflow Java SDK Testing module

This module includes Temporal testing utilities for unit tests and extensions
for testing frameworks.

## Usage

Add `temporal-testing` as a dependency to your `pom.xml`:
```xml
<dependency>
  <groupId>io.temporal</groupId>
  <artifactId>temporal-testing</artifactId>
  <version>N.N.N</version>
</dependency>
```

or to build.gradle:
```
testImplementation("io.temporal:temporal-testing:N.N.N")
```

and if you need JUnit4 or JUnit5 extensions:
```
testImplementation("io.temporal:temporal-testing:N.N.N") {
    capabilities {
        requireCapability("io.temporal:temporal-testing-junit4")
        //requireCapability("io.temporal:temporal-testing-junit5")
    }
}
```

## JUnit4 and Junit5 extensions

For JUnit4 see `io.temporal.testing.TestWorkflowRule` for testing of workflows
For Junit5 see `io.temporal.testing.TestWorkflowExtension` for testing of workflows 
and `io.temporal.testing.TestActivityExtension` for isolated testing of activities

## For isolated testing of activity implementations

See `io.temporal.testing.TestActivityEnvironment` that provides an easy way for isolated testing of
activity implementations without needing to provide workflows calling the activities, triggering the workflows
and bootstrapping a Temporal server or In-memory testing service.
