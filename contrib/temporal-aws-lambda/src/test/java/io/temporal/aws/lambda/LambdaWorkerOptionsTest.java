package io.temporal.aws.lambda;

import static org.junit.Assert.*;

import io.temporal.activity.DynamicActivity;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.common.converter.EncodedValues;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.DynamicWorkflow;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LambdaWorkerOptionsTest {
  private static final WorkerDeploymentVersion VERSION =
      new WorkerDeploymentVersion("deployment", "build");

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void lambdaDefaultsAreAppliedToUnsetOptions() throws IOException {
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());
    options.setTaskQueue("task-queue");

    LambdaWorkerOptions.Materialized materialized = options.materialize(VERSION, "request@arn");

    WorkerOptions workerOptions = materialized.workerOptions;
    assertEquals(2, workerOptions.getMaxConcurrentActivityExecutionSize());
    assertEquals(10, workerOptions.getMaxConcurrentWorkflowTaskExecutionSize());
    assertEquals(2, workerOptions.getMaxConcurrentLocalActivityExecutionSize());
    assertEquals(5, workerOptions.getMaxConcurrentNexusExecutionSize());
    assertEquals(2, workerOptions.getMaxConcurrentWorkflowTaskPollers());
    assertEquals(1, workerOptions.getMaxConcurrentActivityTaskPollers());
    assertEquals(1, workerOptions.getMaxConcurrentNexusTaskPollers());
    assertTrue(workerOptions.isEagerExecutionDisabled());

    WorkerFactoryOptions factoryOptions = materialized.workerFactoryOptions;
    assertEquals(30, factoryOptions.getWorkflowCacheSize());
    assertEquals(30, factoryOptions.getMaxWorkflowThreadCount());

    WorkerDeploymentOptions deploymentOptions = workerOptions.getDeploymentOptions();
    assertTrue(deploymentOptions.isUsingVersioning());
    assertEquals(VERSION, deploymentOptions.getVersion());
    assertEquals(VersioningBehavior.PINNED, deploymentOptions.getDefaultVersioningBehavior());
    assertEquals(Duration.ofSeconds(5), materialized.gracefulShutdownTimeout);
    assertEquals(Duration.ofSeconds(7), materialized.shutdownDeadlineBuffer);
  }

  @Test
  public void userOverridesWinOverLambdaDefaults() throws IOException {
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());
    options.setTaskQueue("task-queue");
    options.getWorkerOptionsBuilder().setMaxConcurrentActivityExecutionSize(11);
    options.getWorkerOptionsBuilder().setMaxConcurrentWorkflowTaskExecutionSize(12);
    options.getWorkerOptionsBuilder().setMaxConcurrentLocalActivityExecutionSize(13);
    options.getWorkerOptionsBuilder().setMaxConcurrentNexusExecutionSize(14);
    options.getWorkerOptionsBuilder().setMaxConcurrentWorkflowTaskPollers(3);
    options.getWorkerOptionsBuilder().setMaxConcurrentActivityTaskPollers(4);
    options.getWorkerOptionsBuilder().setMaxConcurrentNexusTaskPollers(6);
    options
        .getWorkerOptionsBuilder()
        .setDeploymentOptions(
            WorkerDeploymentOptions.newBuilder()
                .setUseVersioning(true)
                .setVersion(new WorkerDeploymentVersion("ignored", "ignored"))
                .setDefaultVersioningBehavior(VersioningBehavior.AUTO_UPGRADE)
                .build());
    options.getWorkerFactoryOptionsBuilder().setWorkflowCacheSize(17);
    options.getWorkerFactoryOptionsBuilder().setMaxWorkflowThreadCount(18);

    LambdaWorkerOptions.Materialized materialized = options.materialize(VERSION, "request@arn");

    WorkerOptions workerOptions = materialized.workerOptions;
    assertEquals(11, workerOptions.getMaxConcurrentActivityExecutionSize());
    assertEquals(12, workerOptions.getMaxConcurrentWorkflowTaskExecutionSize());
    assertEquals(13, workerOptions.getMaxConcurrentLocalActivityExecutionSize());
    assertEquals(14, workerOptions.getMaxConcurrentNexusExecutionSize());
    assertEquals(3, workerOptions.getMaxConcurrentWorkflowTaskPollers());
    assertEquals(4, workerOptions.getMaxConcurrentActivityTaskPollers());
    assertEquals(6, workerOptions.getMaxConcurrentNexusTaskPollers());
    assertTrue(workerOptions.isEagerExecutionDisabled());
    assertEquals(17, materialized.workerFactoryOptions.getWorkflowCacheSize());
    assertEquals(18, materialized.workerFactoryOptions.getMaxWorkflowThreadCount());
    assertTrue(workerOptions.getDeploymentOptions().isUsingVersioning());
    assertEquals(VERSION, workerOptions.getDeploymentOptions().getVersion());
    assertEquals(
        VersioningBehavior.AUTO_UPGRADE,
        workerOptions.getDeploymentOptions().getDefaultVersioningBehavior());
  }

  @Test
  public void temporalTaskQueueEnvPopulatesTaskQueue() throws IOException {
    Map<String, String> env = baseEnv();
    env.put(LambdaWorkerOptions.TEMPORAL_TASK_QUEUE, "env-task-queue");

    LambdaWorkerOptions.Materialized materialized =
        LambdaWorkerOptions.fromEnvironment(env).materialize(VERSION, "request@arn");

    assertEquals("env-task-queue", materialized.taskQueue);
  }

  @Test
  public void missingTaskQueueFailsBeforeRuntimeCreation() throws IOException {
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> options.materialize(VERSION, "request@arn"));

    assertTrue(e.getMessage().contains("Task queue must be set"));
  }

  @Test
  public void gracefulShutdownTimeoutRecomputesDefaultShutdownDeadlineBuffer() throws IOException {
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());
    options.setTaskQueue("task-queue");
    options.setGracefulShutdownTimeout(Duration.ofSeconds(3));

    LambdaWorkerOptions.Materialized materialized = options.materialize(VERSION, "request@arn");

    assertEquals(Duration.ofSeconds(3), materialized.gracefulShutdownTimeout);
    assertEquals(Duration.ofSeconds(5), materialized.shutdownDeadlineBuffer);
  }

  @Test
  public void explicitShutdownDeadlineBufferIsNotRecomputed() throws IOException {
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());
    options.setTaskQueue("task-queue");
    options.setShutdownDeadlineBuffer(Duration.ofSeconds(9));
    options.setGracefulShutdownTimeout(Duration.ofSeconds(3));

    LambdaWorkerOptions.Materialized materialized = options.materialize(VERSION, "request@arn");

    assertEquals(Duration.ofSeconds(3), materialized.gracefulShutdownTimeout);
    assertEquals(Duration.ofSeconds(9), materialized.shutdownDeadlineBuffer);
  }

  @Test
  public void temporalConfigFileTakesPrecedenceOverLambdaTaskRoot() throws IOException {
    File explicitConfig = temporaryFolder.newFile("explicit.toml");
    Files.write(
        explicitConfig.toPath(),
        "[profile.default]\nnamespace = \"explicit\"\n".getBytes(StandardCharsets.UTF_8));
    File taskRoot = temporaryFolder.newFolder("task-root");
    Files.write(
        new File(taskRoot, "temporal.toml").toPath(),
        "[profile.default]\nnamespace = \"lambda-root\"\n".getBytes(StandardCharsets.UTF_8));

    Map<String, String> env = new HashMap<>();
    env.put(LambdaWorkerOptions.TEMPORAL_CONFIG_FILE, explicitConfig.getAbsolutePath());
    env.put(LambdaWorkerOptions.LAMBDA_TASK_ROOT, taskRoot.getAbsolutePath());

    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(env);
    options.setTaskQueue("task-queue");
    WorkflowClientOptions clientOptions = options.materialize(VERSION, "request@arn").clientOptions;

    assertEquals("explicit", clientOptions.getNamespace());
  }

  @Test
  public void lambdaTaskRootTemporalTomlWinsOverCwdTemporalToml() throws IOException {
    File taskRoot = temporaryFolder.newFolder("task-root");
    writeConfig(taskRoot, "lambda-root");
    File cwd = temporaryFolder.newFolder("cwd");
    writeConfig(cwd, "cwd");

    Map<String, String> env = new HashMap<>();
    env.put(LambdaWorkerOptions.LAMBDA_TASK_ROOT, taskRoot.getAbsolutePath());

    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(env, cwd);
    options.setTaskQueue("task-queue");

    assertEquals(
        "lambda-root", options.materialize(VERSION, "request@arn").clientOptions.getNamespace());
  }

  @Test
  public void cwdTemporalTomlIsUsedWhenLambdaTaskRootIsUnset() throws IOException {
    File cwd = temporaryFolder.newFolder("cwd");
    writeConfig(cwd, "cwd");

    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(new HashMap<>(), cwd);
    options.setTaskQueue("task-queue");

    assertEquals("cwd", options.materialize(VERSION, "request@arn").clientOptions.getNamespace());
  }

  @Test
  public void cwdTemporalTomlIsUsedWhenLambdaTaskRootHasNoTemporalToml() throws IOException {
    File taskRoot = temporaryFolder.newFolder("task-root");
    File cwd = temporaryFolder.newFolder("cwd");
    writeConfig(cwd, "cwd");
    Map<String, String> env = new HashMap<>();
    env.put(LambdaWorkerOptions.LAMBDA_TASK_ROOT, taskRoot.getAbsolutePath());

    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(env, cwd);
    options.setTaskQueue("task-queue");

    assertEquals("cwd", options.materialize(VERSION, "request@arn").clientOptions.getNamespace());
  }

  @Test
  public void envconfigDefaultsAreUsedWhenNoConfigFileCandidateExists() throws IOException {
    File cwd = temporaryFolder.newFolder("cwd");

    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(new HashMap<>(), cwd);
    options.setTaskQueue("task-queue");

    assertEquals(
        "default", options.materialize(VERSION, "request@arn").clientOptions.getNamespace());
  }

  @Test
  public void deploymentVersionMustHaveDeploymentNameAndBuildId() {
    assertThrows(
        IllegalArgumentException.class,
        () -> LambdaWorkerOptions.validateVersion(new WorkerDeploymentVersion("", "build")));
    assertThrows(
        IllegalArgumentException.class,
        () -> LambdaWorkerOptions.validateVersion(new WorkerDeploymentVersion("deployment", "")));
  }

  @Test
  public void dynamicRegistrationMethodsRejectNullInputs() throws IOException {
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());

    assertThrows(
        NullPointerException.class,
        () ->
            options.registerDynamicWorkflowImplementationType(
                (Class<? extends DynamicWorkflow>) null));
    assertThrows(
        NullPointerException.class,
        () -> options.registerDynamicWorkflowImplementationType(null, TestDynamicWorkflow.class));
    assertThrows(
        NullPointerException.class,
        () ->
            options.registerDynamicWorkflowImplementationType(
                WorkflowImplementationOptions.getDefaultInstance(), null));
    assertThrows(
        NullPointerException.class, () -> options.registerDynamicActivityImplementation(null));
  }

  private Map<String, String> baseEnv() {
    Map<String, String> env = new HashMap<>();
    env.put(LambdaWorkerOptions.TEMPORAL_CONFIG_FILE, "/nonexistent/temporal.toml");
    return env;
  }

  private void writeConfig(File directory, String namespace) throws IOException {
    Files.write(
        new File(directory, "temporal.toml").toPath(),
        ("[profile.default]\nnamespace = \"" + namespace + "\"\n")
            .getBytes(StandardCharsets.UTF_8));
  }

  private static final class TestDynamicWorkflow implements DynamicWorkflow {
    @Override
    public Object execute(EncodedValues args) {
      return null;
    }
  }

  private static final class TestDynamicActivity implements DynamicActivity {
    @Override
    public Object execute(EncodedValues args) {
      return null;
    }
  }
}
