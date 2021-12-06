# Temporal Workflow Java SDK Testing module

This module includes an in-memory Temporal testing service implementation,
supporting classes for unit tests and extensions for testing framework

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

## In-memory Temporal testing service 

This service allows to run a test-only in-memory implementation of Temporal server API.
The entry point is `io.temporal.testing.TestWorkflowEnvironment`

## JUnit4 and Junit5 extensions

For JUnit4 see `io.temporal.testing.TestWorkflowRule` for testing of workflows
For Junit5 see `io.temporal.testing.TestWorkflowExtension` for testing of workflows 
and `io.temporal.testing.TestActivityExtension` for isolated testing of activities

## For isolated testing of activity implementations

See `io.temporal.testing.TestActivityEnvironment` that provides an easy way for isolated testing of
activity implementations without needing to provide workflows calling the activities, triggering the workflows
and bootstrapping a Temporal server or In-memory testing service.

## To build a test service using GraalVM native-image

From the root of the java-sdk repo:
```
./gradlew :temporal-testing:copyDependencies
```
```
native-image -jar "./temporal-testing/build/libs/temporal-testing-1.6.0-SNAPSHOT.jar" test-server --class-path "./temporal-testing/build/dependencies/*" \
--allow-incomplete-classpath --no-fallback
```
Additional `native-image` flags to consider:
1. `--static` to build a fully static binary. _[Not supported](https://developer.apple.com/library/archive/qa/qa1118/_index.html) for MacOS._

### Notes on installing GraalVM
1. Install [prerequisites](https://www.graalvm.org/reference-manual/native-image/#prerequisites)
2. Install [sdkman](https://sdkman.io/install)
3. `sdk install java 21.2.0.r11-grl` GraalVM on JDK 11
4. `sdk use java 21.2.0.r11-grl`
5. `gu install native-image`, where `gu` is GraalVM Updater.
6. After these steps, if sdkman is configured properly, you can switch to GraalVM JDK any time in the shell by using `sdk use java 21.2.0.r11-grl` which will make `native-image` tool also available
