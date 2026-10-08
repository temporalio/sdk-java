# Temporal Spring AI has moved

The Spring AI integration is developed and released in
[temporalio/ai-integrations](https://github.com/temporalio/ai-integrations/tree/main/java/spring-ai)
under the independently versioned Maven coordinate `io.temporal:spring-ai`.
The Java package remains `io.temporal.springai`.

See the [standalone usage guide](https://github.com/temporalio/ai-integrations/tree/main/java/spring-ai)
for installation and migration instructions. The integration's releases are
versioned independently of the Java SDK.

This directory contains only a relocation POM from the next SDK version of
`io.temporal:temporal-spring-ai` to `io.temporal:spring-ai:0.1.0`. It has no source,
tests, JARs, or Spring AI dependencies. The SDK BOM retains the old coordinate for
this final relocation release.

Publish the new coordinate to Maven Central before merging this cutover, because
merging also publishes the SDK snapshot relocation. After publishing the one-time
relocation POM, remove this Gradle project, its BOM constraint, and its CODEOWNERS
entry from sdk-java. Existing releases remain available at their original
coordinates.
