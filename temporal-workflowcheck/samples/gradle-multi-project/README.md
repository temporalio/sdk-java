# Temporal Workflow Check for Java - Gradle Sample

This sample shows how to incorporate `workflowcheck` into a Gradle build that has multiple projects. Currently there are
no published releases, so this example includes the primary build in the [settings.gradle](settings.gradle) file. But
users may just want to reference a published JAR when it is available.

To run:

    gradlew check

This will output something like:

```
Analyzing classpath for classes with workflow methods...
Found 1 class(es) with workflow methods
Workflow method io.temporal.workflowcheck.sample.gradlemulti.workflows.MyWorkflowImpl.errorAtNight() (declared on io.temporal.workflowcheck.sample.gradlemulti.workflows.MyWorkflow) has 1 invalid member access:
  MyWorkflowImpl.java:10 invokes java.time.LocalTime.now() which is configured as invalid
```