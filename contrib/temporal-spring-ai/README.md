# Temporal Spring AI has moved

The Spring AI integration is developed and released in
[temporalio/ai-integrations](https://github.com/temporalio/ai-integrations/tree/main/java/spring-ai)
under the independently versioned Maven coordinate `io.temporal:spring-ai`.
Version [0.1.0 is available on Maven Central](https://central.sonatype.com/artifact/io.temporal/spring-ai/0.1.0).
The Java package remains `io.temporal.springai`.

See the [standalone usage guide](https://github.com/temporalio/ai-integrations/tree/main/java/spring-ai)
for installation and migration instructions. The integration's releases are
versioned independently of the Java SDK.

The standalone 0.1.0 release requires Java 17 or later, Spring AI 2, Spring Boot 4,
and Temporal Java SDK 1.40.0 or later. Spring AI 1 / Spring Boot 3 applications
should remain on `io.temporal:temporal-spring-ai:1.40.x` until they are ready to
migrate. Spring AI 1 workflow histories are not replay-compatible with the new
integration; complete existing executions or use separate worker deployments.

This directory contains only a relocation POM from the next SDK version of
`io.temporal:temporal-spring-ai` to `io.temporal:spring-ai:0.1.0`. It has no source,
tests, JARs, or Spring AI dependencies. The SDK BOM retains the old coordinate for
this final relocation release.

After publishing the one-time relocation POM, remove this Gradle project, its BOM
constraint, and its CODEOWNERS entry from sdk-java. Existing releases remain
available at their original coordinates.
