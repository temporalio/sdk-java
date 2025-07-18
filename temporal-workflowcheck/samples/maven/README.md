# Temporal Workflow Check for Java - Maven Sample

This sample shows how to incorporate `workflowcheck` into a Maven build. Currently there are no published releases, so
this example expects the primary Gradle to publish the JAR to a local Maven repo that this project references. In the
future, users may just want to reference a published JAR when it is available.

To run, first publish the `workflowcheck` JAR to a local repository. ⚠️ WARNING: While there remain no published
releases of workflowcheck, it is currently undocumented on how to publish to a local/disk Maven repo.

Now with the local repository present, can run the following from this dir:

    mvn -U verify

Note, this is a sample using the local repository so that's why we have `-U`. For normal use, `mvn verify` without the
`-U` can be used (and the `<pluginRepositories>` section of the `pom.xml` can be removed).

This will output something like:

```
Analyzing classpath for classes with workflow methods...
Found 1 class(es) with workflow methods
Workflow method io.temporal.workflowcheck.sample.maven.MyWorkflowImpl.errorAtNight() (declared on io.temporal.workflowcheck.sample.maven.MyWorkflow) has 1 invalid member access:
  MyWorkflowImpl.java:11 invokes java.time.LocalTime.now() which is configured as invalid
```