# Temporal Workflow Check for Java - Maven Sample

This sample shows how to incorporate `workflowcheck` into a Maven build.

To run, execute the following command from this dir:

    mvn verify

This will output something like:

```
Analyzing classpath for classes with workflow methods...
Found 1 class(es) with workflow methods
Workflow method io.temporal.workflowcheck.sample.maven.MyWorkflowImpl.errorAtNight() (declared on io.temporal.workflowcheck.sample.maven.MyWorkflow) has 1 invalid member access:
  MyWorkflowImpl.java:11 invokes java.time.LocalTime.now() which is configured as invalid
```