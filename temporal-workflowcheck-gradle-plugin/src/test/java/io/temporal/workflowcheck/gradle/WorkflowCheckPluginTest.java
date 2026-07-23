package io.temporal.workflowcheck.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WorkflowCheckPluginTest {

  private static final String TEMPORAL_SDK_VERSION = "1.36.0";

  @Rule public final TemporaryFolder testProjectDir = new TemporaryFolder();

  private File buildFile;

  @Before
  public void setUp() throws IOException {
    write("settings.gradle", "rootProject.name = 'workflowcheck-plugin-test'\n");
    buildFile = testProjectDir.newFile("build.gradle");
    writeBuildFile("");
  }

  @Test
  public void passesForValidWorkflow() throws IOException {
    writeValidWorkflow();

    BuildResult result = run("workflowCheck");

    assertEquals(TaskOutcome.SUCCESS, result.task(":workflowCheck").getOutcome());
  }

  @Test
  public void failsForDeterminismViolation() throws IOException {
    writeWorkflowWithThreadSleep();

    BuildResult result = runAndFail("workflowCheck");

    assertEquals(TaskOutcome.FAILED, result.task(":workflowCheck").getOutcome());
    assertTrue(result.getOutput().contains("invalid member access"));
    assertTrue(result.getOutput().contains("determinism violations found"));
  }

  @Test
  public void reportsViolationsButSucceedsWhenFailOnViolationIsFalse() throws IOException {
    writeBuildFile("workflowCheck {\n    failOnViolation = false\n}\n");
    writeWorkflowWithThreadSleep();

    BuildResult result = run("workflowCheck");

    assertEquals(TaskOutcome.SUCCESS, result.task(":workflowCheck").getOutcome());
    assertTrue(result.getOutput().contains("invalid member access"));
  }

  @Test
  public void showValidOptionIncludesValidMethodsInOutput() throws IOException {
    writeValidWorkflow();

    BuildResult result = run("workflowCheck", "--show-valid");

    assertEquals(TaskOutcome.SUCCESS, result.task(":workflowCheck").getOutcome());
    assertTrue(result.getOutput().contains("is valid"));
  }

  @Test
  public void customConfigFileIsRespected() throws IOException {
    writeBuildFile(
        "workflowCheck {\n" + "    configFiles.from('check/overrides.properties')\n" + "}\n");
    write(
        "check/overrides.properties",
        "temporal.workflowcheck.invalid.java/lang/Thread.sleep=false\n");
    writeWorkflowWithThreadSleep();

    BuildResult result = run("workflowCheck");

    assertEquals(TaskOutcome.SUCCESS, result.task(":workflowCheck").getOutcome());
  }

  @Test
  public void checkLifecycleRunsWorkflowCheck() throws IOException {
    writeValidWorkflow();

    BuildResult result = run("check");

    assertEquals(TaskOutcome.SUCCESS, result.task(":workflowCheck").getOutcome());
  }

  @Test
  public void resolvesAnalyzerFromConfiguredSourceSetRuntimeClasspath() throws IOException {
    writeBuildFile(
        "    testImplementation 'io.temporal:temporal-sdk:" + TEMPORAL_SDK_VERSION + "'\n",
        "workflowCheck {\n    sourceSets = ['test']\n}\n");
    writeTestWorkflow();

    BuildResult result = run("workflowCheck");

    assertEquals(TaskOutcome.SUCCESS, result.task(":workflowCheck").getOutcome());
    assertTrue(result.getOutput().contains("Found 1 class(es) with workflow methods"));
  }

  @Test
  public void taskIsUpToDateOnSecondRun() throws IOException {
    writeValidWorkflow();

    BuildResult firstResult = run("workflowCheck");
    BuildResult secondResult = run("workflowCheck");

    assertEquals(TaskOutcome.SUCCESS, firstResult.task(":workflowCheck").getOutcome());
    assertEquals(TaskOutcome.UP_TO_DATE, secondResult.task(":workflowCheck").getOutcome());
  }

  private BuildResult run(String... arguments) {
    return runner(arguments).build();
  }

  private BuildResult runAndFail(String... arguments) {
    return runner(arguments).buildAndFail();
  }

  private GradleRunner runner(String... arguments) {
    return GradleRunner.create()
        .withProjectDir(testProjectDir.getRoot())
        .withPluginClasspath()
        .withArguments(arguments)
        .forwardOutput();
  }

  private void writeBuildFile(String extraConfiguration) throws IOException {
    writeBuildFile(
        "    implementation 'io.temporal:temporal-sdk:" + TEMPORAL_SDK_VERSION + "'\n",
        extraConfiguration);
  }

  private void writeBuildFile(String dependencies, String extraConfiguration) throws IOException {
    write(
        buildFile,
        "plugins {\n"
            + "    id 'java'\n"
            + "    id 'io.temporal.workflowcheck'\n"
            + "}\n"
            + "\n"
            + "repositories {\n"
            + "    mavenCentral()\n"
            + "}\n"
            + "\n"
            + "dependencies {\n"
            + dependencies
            + "}\n"
            + "\n"
            + extraConfiguration);
  }

  private void writeValidWorkflow() throws IOException {
    writeWorkflowInterface();
    write(
        "src/main/java/com/example/MyWorkflowImpl.java",
        "package com.example;\n"
            + "\n"
            + "public class MyWorkflowImpl implements MyWorkflow {\n"
            + "  @Override\n"
            + "  public String run(String input) {\n"
            + "    return \"Hello, \" + input;\n"
            + "  }\n"
            + "}\n");
  }

  private void writeWorkflowWithThreadSleep() throws IOException {
    writeWorkflowInterface();
    write(
        "src/main/java/com/example/MyWorkflowImpl.java",
        "package com.example;\n"
            + "\n"
            + "public class MyWorkflowImpl implements MyWorkflow {\n"
            + "  @Override\n"
            + "  public String run(String input) {\n"
            + "    try {\n"
            + "      Thread.sleep(1000);\n"
            + "    } catch (InterruptedException e) {\n"
            + "      // Ignore for test.\n"
            + "    }\n"
            + "    return \"Hello, \" + input;\n"
            + "  }\n"
            + "}\n");
  }

  private void writeWorkflowInterface() throws IOException {
    writeWorkflowInterface("src/main/java/com/example/MyWorkflow.java");
  }

  private void writeTestWorkflow() throws IOException {
    writeWorkflowInterface("src/test/java/com/example/MyWorkflow.java");
    write(
        "src/test/java/com/example/MyWorkflowImpl.java",
        "package com.example;\n"
            + "\n"
            + "public class MyWorkflowImpl implements MyWorkflow {\n"
            + "  @Override\n"
            + "  public String run(String input) {\n"
            + "    return \"Hello, \" + input;\n"
            + "  }\n"
            + "}\n");
  }

  private void writeWorkflowInterface(String relativePath) throws IOException {
    write(
        relativePath,
        "package com.example;\n"
            + "\n"
            + "import io.temporal.workflow.WorkflowInterface;\n"
            + "import io.temporal.workflow.WorkflowMethod;\n"
            + "\n"
            + "@WorkflowInterface\n"
            + "public interface MyWorkflow {\n"
            + "  @WorkflowMethod\n"
            + "  String run(String input);\n"
            + "}\n");
  }

  private void write(String relativePath, String content) throws IOException {
    File file = new File(testProjectDir.getRoot(), relativePath);
    write(file, content);
  }

  private void write(File file, String content) throws IOException {
    File parentFile = file.getParentFile();
    if (parentFile != null) {
      parentFile.mkdirs();
    }
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
  }
}
