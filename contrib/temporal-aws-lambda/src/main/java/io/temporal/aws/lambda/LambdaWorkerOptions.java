package io.temporal.aws.lambda;

import io.temporal.activity.DynamicActivity;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.common.converter.EncodedValues;
import io.temporal.envconfig.ClientConfigProfile;
import io.temporal.envconfig.LoadClientConfigProfileOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.DynamicWorkflow;
import io.temporal.workflow.Functions;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for Temporal workers running inside AWS Lambda invocations.
 *
 * <p>Instances are immutable snapshots. Lambda handlers copy snapshots for each invocation so
 * invocation identity and optional per-invocation configuration can be applied without mutating the
 * base options.
 */
public final class LambdaWorkerOptions {
  public static final String TEMPORAL_TASK_QUEUE = "TEMPORAL_TASK_QUEUE";
  public static final String TEMPORAL_CONFIG_FILE = "TEMPORAL_CONFIG_FILE";
  public static final String LAMBDA_TASK_ROOT = "LAMBDA_TASK_ROOT";

  static final Duration DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
  static final Duration DEFAULT_SHUTDOWN_HOOKS_AND_STUBS_TIMEOUT = Duration.ofSeconds(2);
  static final Duration DEFAULT_SHUTDOWN_DEADLINE_BUFFER =
      DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT.plus(DEFAULT_SHUTDOWN_HOOKS_AND_STUBS_TIMEOUT);

  private static final int DEFAULT_MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE = 2;
  private static final int DEFAULT_MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE = 10;
  private static final int DEFAULT_MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE = 2;
  private static final int DEFAULT_MAX_CONCURRENT_NEXUS_EXECUTION_SIZE = 5;
  private static final int DEFAULT_MAX_CONCURRENT_WORKFLOW_TASK_POLLERS = 2;
  private static final int DEFAULT_MAX_CONCURRENT_ACTIVITY_TASK_POLLERS = 1;
  private static final int DEFAULT_MAX_CONCURRENT_NEXUS_TASK_POLLERS = 1;
  private static final int DEFAULT_WORKFLOW_CACHE_SIZE = 30;
  private static final int DEFAULT_MAX_WORKFLOW_THREAD_COUNT = 30;

  private final WorkflowServiceStubsOptions workflowServiceStubsOptions;
  private final WorkflowClientOptions workflowClientOptions;
  private final WorkerFactoryOptions workerFactoryOptions;
  private final WorkerOptions workerOptions;
  private final List<Registration> registrations;
  private final List<Runnable> shutdownHooks;
  private final String taskQueue;
  private final Duration gracefulShutdownTimeout;
  private final Duration shutdownDeadlineBuffer;
  private final boolean shutdownDeadlineBufferExplicit;

  private LambdaWorkerOptions(
      WorkflowServiceStubsOptions workflowServiceStubsOptions,
      WorkflowClientOptions workflowClientOptions,
      WorkerFactoryOptions workerFactoryOptions,
      WorkerOptions workerOptions,
      List<Registration> registrations,
      List<Runnable> shutdownHooks,
      String taskQueue,
      Duration gracefulShutdownTimeout,
      Duration shutdownDeadlineBuffer,
      boolean shutdownDeadlineBufferExplicit) {
    this.workflowServiceStubsOptions = workflowServiceStubsOptions;
    this.workflowClientOptions = workflowClientOptions;
    this.workerFactoryOptions = workerFactoryOptions;
    this.workerOptions = workerOptions;
    this.registrations = Collections.unmodifiableList(new ArrayList<>(registrations));
    this.shutdownHooks = Collections.unmodifiableList(new ArrayList<>(shutdownHooks));
    this.taskQueue = taskQueue;
    this.gracefulShutdownTimeout = gracefulShutdownTimeout;
    this.shutdownDeadlineBuffer = shutdownDeadlineBuffer;
    this.shutdownDeadlineBufferExplicit = shutdownDeadlineBufferExplicit;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(LambdaWorkerOptions options) {
    return new Builder(options);
  }

  /** Loads Temporal client configuration from the process environment into a new builder. */
  public static Builder newBuilderFromEnvironment() throws IOException {
    return newBuilderFromEnvironment(System.getenv());
  }

  /**
   * Loads Temporal client configuration from the provided environment values into a new builder.
   */
  public static Builder newBuilderFromEnvironment(Map<String, String> env) throws IOException {
    return newBuilderFromEnvironment(env, new File("."));
  }

  static Builder newBuilderFromEnvironment(Map<String, String> env, File cwd) throws IOException {
    ClientConfigProfile profile =
        ClientConfigProfile.load(
            LoadClientConfigProfileOptions.newBuilder()
                .setConfigFilePath(resolveConfigFilePath(env, cwd))
                .setEnvOverrides(env)
                .build());
    return new Builder(profile, env);
  }

  static String resolveConfigFilePath(Map<String, String> env) {
    return resolveConfigFilePath(env, new File("."));
  }

  static String resolveConfigFilePath(Map<String, String> env, File cwd) {
    String configured = nonEmptyEnv(env, TEMPORAL_CONFIG_FILE);
    if (configured != null) {
      return configured;
    }

    String taskRoot = nonEmptyEnv(env, LAMBDA_TASK_ROOT);
    if (taskRoot != null) {
      File lambdaConfig = new File(taskRoot, "temporal.toml");
      if (isReadableFile(lambdaConfig)) {
        return lambdaConfig.getAbsolutePath();
      }
    }

    File cwdConfig = new File(Objects.requireNonNull(cwd, "cwd"), "temporal.toml");
    return isReadableFile(cwdConfig) ? cwdConfig.getAbsolutePath() : null;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public WorkflowServiceStubsOptions getWorkflowServiceStubsOptions() {
    return workflowServiceStubsOptions;
  }

  public WorkflowClientOptions getWorkflowClientOptions() {
    return workflowClientOptions;
  }

  public WorkerFactoryOptions getWorkerFactoryOptions() {
    return workerFactoryOptions;
  }

  public WorkerOptions getWorkerOptions() {
    return workerOptions;
  }

  public String getTaskQueue() {
    return taskQueue;
  }

  public Duration getGracefulShutdownTimeout() {
    return gracefulShutdownTimeout;
  }

  public Duration getShutdownDeadlineBuffer() {
    return shutdownDeadlineBuffer;
  }

  Materialized materialize(WorkerDeploymentVersion version, String invocationIdentity) {
    return prepare(version).materialize(invocationIdentity);
  }

  Prepared prepare(WorkerDeploymentVersion version) {
    validateVersion(version);
    if (isNullOrEmpty(taskQueue)) {
      throw new IllegalStateException(
          "Task queue must be set with LambdaWorkerOptions.Builder#setTaskQueue or TEMPORAL_TASK_QUEUE");
    }
    validateShutdownConfiguration();

    WorkflowClientOptions rawClientOptions = workflowClientOptions;
    WorkerOptions rawWorkerOptions = workerOptions;
    WorkerFactoryOptions rawFactoryOptions = workerFactoryOptions;

    WorkflowClientOptions.Builder clientOptionsBuilder =
        WorkflowClientOptions.newBuilder(rawClientOptions);
    WorkerOptions.Builder workerOptionsBuilder = WorkerOptions.newBuilder(rawWorkerOptions);
    WorkerFactoryOptions.Builder factoryOptionsBuilder =
        WorkerFactoryOptions.newBuilder(rawFactoryOptions);

    if (rawClientOptions.getIdentity() == null && rawWorkerOptions.getIdentity() != null) {
      clientOptionsBuilder.setIdentity(rawWorkerOptions.getIdentity());
    } else if (rawWorkerOptions.getIdentity() == null && rawClientOptions.getIdentity() != null) {
      workerOptionsBuilder.setIdentity(rawClientOptions.getIdentity());
    }

    applyLambdaWorkerDefaults(rawWorkerOptions, workerOptionsBuilder, version);
    applyLambdaFactoryDefaults(rawFactoryOptions, factoryOptionsBuilder);

    return new Prepared(
        WorkflowServiceStubsOptions.newBuilder(workflowServiceStubsOptions)
            .validateAndBuildWithDefaults(),
        clientOptionsBuilder.build(),
        factoryOptionsBuilder.validateAndBuildWithDefaults(),
        taskQueue,
        workerOptionsBuilder.validateAndBuildWithDefaults(),
        gracefulShutdownTimeout,
        shutdownDeadlineBuffer,
        new ArrayList<>(registrations),
        new ArrayList<>(shutdownHooks));
  }

  private void validateShutdownConfiguration() {
    if (shutdownDeadlineBuffer.compareTo(gracefulShutdownTimeout) < 0) {
      throw new IllegalStateException(
          "shutdownDeadlineBuffer must be greater than or equal to gracefulShutdownTimeout");
    }
  }

  private static void applyLambdaWorkerDefaults(
      WorkerOptions rawOptions, WorkerOptions.Builder builder, WorkerDeploymentVersion version) {
    if (rawOptions.getWorkerTuner() == null) {
      if (rawOptions.getMaxConcurrentActivityExecutionSize() == 0) {
        builder.setMaxConcurrentActivityExecutionSize(
            DEFAULT_MAX_CONCURRENT_ACTIVITY_EXECUTION_SIZE);
      }
      if (rawOptions.getMaxConcurrentWorkflowTaskExecutionSize() == 0) {
        builder.setMaxConcurrentWorkflowTaskExecutionSize(
            DEFAULT_MAX_CONCURRENT_WORKFLOW_TASK_EXECUTION_SIZE);
      }
      if (rawOptions.getMaxConcurrentLocalActivityExecutionSize() == 0) {
        builder.setMaxConcurrentLocalActivityExecutionSize(
            DEFAULT_MAX_CONCURRENT_LOCAL_ACTIVITY_EXECUTION_SIZE);
      }
      if (rawOptions.getMaxConcurrentNexusExecutionSize() == 0) {
        builder.setMaxConcurrentNexusExecutionSize(DEFAULT_MAX_CONCURRENT_NEXUS_EXECUTION_SIZE);
      }
    }

    if (rawOptions.getWorkflowTaskPollersBehavior() == null
        && rawOptions.getMaxConcurrentWorkflowTaskPollers() == 0) {
      builder.setMaxConcurrentWorkflowTaskPollers(DEFAULT_MAX_CONCURRENT_WORKFLOW_TASK_POLLERS);
    }
    if (rawOptions.getActivityTaskPollersBehavior() == null
        && rawOptions.getMaxConcurrentActivityTaskPollers() == 0) {
      builder.setMaxConcurrentActivityTaskPollers(DEFAULT_MAX_CONCURRENT_ACTIVITY_TASK_POLLERS);
    }
    if (rawOptions.getNexusTaskPollersBehavior() == null
        && rawOptions.getMaxConcurrentNexusTaskPollers() == 0) {
      builder.setMaxConcurrentNexusTaskPollers(DEFAULT_MAX_CONCURRENT_NEXUS_TASK_POLLERS);
    }

    builder.setDisableEagerExecution(true);
    builder.setDeploymentOptions(
        forcedDeploymentOptions(rawOptions.getDeploymentOptions(), version));
  }

  private static void applyLambdaFactoryDefaults(
      WorkerFactoryOptions rawOptions, WorkerFactoryOptions.Builder builder) {
    if (rawOptions.getWorkflowCacheSize() == 0) {
      builder.setWorkflowCacheSize(DEFAULT_WORKFLOW_CACHE_SIZE);
    }
    if (rawOptions.getMaxWorkflowThreadCount() == 0) {
      builder.setMaxWorkflowThreadCount(DEFAULT_MAX_WORKFLOW_THREAD_COUNT);
    }
  }

  private static WorkerDeploymentOptions forcedDeploymentOptions(
      WorkerDeploymentOptions existing, WorkerDeploymentVersion version) {
    VersioningBehavior behavior = VersioningBehavior.PINNED;
    if (existing != null
        && existing.getDefaultVersioningBehavior() != VersioningBehavior.UNSPECIFIED) {
      behavior = existing.getDefaultVersioningBehavior();
    }

    return WorkerDeploymentOptions.newBuilder()
        .setUseVersioning(true)
        .setVersion(version)
        .setDefaultVersioningBehavior(behavior)
        .build();
  }

  static void validateVersion(WorkerDeploymentVersion version) {
    Objects.requireNonNull(version, "version");
    if (isNullOrEmpty(version.getDeploymentName())) {
      throw new IllegalArgumentException("Worker deployment name must be non-empty");
    }
    if (isNullOrEmpty(version.getBuildId())) {
      throw new IllegalArgumentException("Worker deployment build ID must be non-empty");
    }
  }

  private static Duration requireNonNegative(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }

  private static boolean isReadableFile(File file) {
    return file.isFile() && file.canRead();
  }

  private static String nonEmptyEnv(Map<String, String> env, String name) {
    if (env == null) {
      return null;
    }
    String value = env.get(name);
    return isNullOrEmpty(value) ? null : value;
  }

  private static boolean isNullOrEmpty(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static Class<?>[] copyClasses(Class<?>... classes) {
    Objects.requireNonNull(classes, "classes");
    for (Class<?> workflowImplementationClass : classes) {
      Objects.requireNonNull(workflowImplementationClass, "workflowImplementationClass");
    }
    return Arrays.copyOf(classes, classes.length);
  }

  private static Object[] copyObjects(Object[] objects, String name) {
    Objects.requireNonNull(objects, name);
    return Arrays.copyOf(objects, objects.length);
  }

  public static final class Builder {
    private final WorkflowServiceStubsOptions.Builder workflowServiceStubsOptionsBuilder;
    private final WorkflowClientOptions.Builder workflowClientOptionsBuilder;
    private final WorkerFactoryOptions.Builder workerFactoryOptionsBuilder;
    private final WorkerOptions.Builder workerOptionsBuilder;
    private final List<Registration> registrations = new ArrayList<>();
    private final List<Runnable> shutdownHooks = new ArrayList<>();

    private String taskQueue;
    private Duration gracefulShutdownTimeout = DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT;
    private Duration shutdownDeadlineBuffer = DEFAULT_SHUTDOWN_DEADLINE_BUFFER;
    private boolean shutdownDeadlineBufferExplicit;

    private Builder() {
      this.workflowServiceStubsOptionsBuilder = WorkflowServiceStubsOptions.newBuilder();
      this.workflowClientOptionsBuilder = WorkflowClientOptions.newBuilder();
      this.workerFactoryOptionsBuilder = WorkerFactoryOptions.newBuilder();
      this.workerOptionsBuilder = WorkerOptions.newBuilder();
    }

    private Builder(ClientConfigProfile profile, Map<String, String> env) {
      this.workflowServiceStubsOptionsBuilder =
          WorkflowServiceStubsOptions.newBuilder(profile.toWorkflowServiceStubsOptions());
      this.workflowClientOptionsBuilder =
          WorkflowClientOptions.newBuilder(profile.toWorkflowClientOptions());
      this.workerFactoryOptionsBuilder = WorkerFactoryOptions.newBuilder();
      this.workerOptionsBuilder = WorkerOptions.newBuilder();
      this.taskQueue = nonEmptyEnv(env, TEMPORAL_TASK_QUEUE);
    }

    private Builder(LambdaWorkerOptions options) {
      Objects.requireNonNull(options, "options");
      this.workflowServiceStubsOptionsBuilder =
          WorkflowServiceStubsOptions.newBuilder(options.workflowServiceStubsOptions);
      this.workflowClientOptionsBuilder =
          WorkflowClientOptions.newBuilder(options.workflowClientOptions);
      this.workerFactoryOptionsBuilder =
          WorkerFactoryOptions.newBuilder(options.workerFactoryOptions);
      this.workerOptionsBuilder = WorkerOptions.newBuilder(options.workerOptions);
      this.registrations.addAll(options.registrations);
      this.shutdownHooks.addAll(options.shutdownHooks);
      this.taskQueue = options.taskQueue;
      this.gracefulShutdownTimeout = options.gracefulShutdownTimeout;
      this.shutdownDeadlineBuffer = options.shutdownDeadlineBuffer;
      this.shutdownDeadlineBufferExplicit = options.shutdownDeadlineBufferExplicit;
    }

    /** Returns the builder used to prepare {@link WorkflowServiceStubsOptions}. */
    public WorkflowServiceStubsOptions.Builder getWorkflowServiceStubsOptionsBuilder() {
      return workflowServiceStubsOptionsBuilder;
    }

    /** Returns the builder used to prepare {@link WorkflowClientOptions}. */
    public WorkflowClientOptions.Builder getWorkflowClientOptionsBuilder() {
      return workflowClientOptionsBuilder;
    }

    /** Returns the builder used to prepare {@link WorkerFactoryOptions}. */
    public WorkerFactoryOptions.Builder getWorkerFactoryOptionsBuilder() {
      return workerFactoryOptionsBuilder;
    }

    /** Returns the builder used to prepare {@link WorkerOptions}. */
    public WorkerOptions.Builder getWorkerOptionsBuilder() {
      return workerOptionsBuilder;
    }

    public String getTaskQueue() {
      return taskQueue;
    }

    /** Sets the Temporal task queue polled by the per-invocation worker. */
    public Builder setTaskQueue(String taskQueue) {
      this.taskQueue = taskQueue;
      return this;
    }

    public Duration getGracefulShutdownTimeout() {
      return gracefulShutdownTimeout;
    }

    /**
     * Sets how long worker shutdown waits for pollers and executions to stop.
     *
     * <p>If {@link #setShutdownDeadlineBuffer(Duration)} has not been called, the shutdown deadline
     * buffer is recomputed as this timeout plus a 2 second hook and service stubs margin.
     */
    public Builder setGracefulShutdownTimeout(Duration gracefulShutdownTimeout) {
      this.gracefulShutdownTimeout =
          requireNonNegative(gracefulShutdownTimeout, "gracefulShutdownTimeout");
      if (!shutdownDeadlineBufferExplicit) {
        shutdownDeadlineBuffer =
            this.gracefulShutdownTimeout.plus(DEFAULT_SHUTDOWN_HOOKS_AND_STUBS_TIMEOUT);
      }
      return this;
    }

    public Duration getShutdownDeadlineBuffer() {
      return shutdownDeadlineBuffer;
    }

    List<Runnable> getShutdownHooks() {
      return new ArrayList<>(shutdownHooks);
    }

    /**
     * Sets the full shutdown window reserved at the end of the Lambda invocation.
     *
     * <p>The worker stops when remaining invocation time reaches this buffer. The default is 7
     * seconds, made up of the 5 second graceful shutdown timeout and a 2 second hook and service
     * stubs margin. This buffer must be greater than or equal to {@link
     * #getGracefulShutdownTimeout()}.
     */
    public Builder setShutdownDeadlineBuffer(Duration shutdownDeadlineBuffer) {
      this.shutdownDeadlineBuffer =
          requireNonNegative(shutdownDeadlineBuffer, "shutdownDeadlineBuffer");
      shutdownDeadlineBufferExplicit = true;
      return this;
    }

    public Builder registerWorkflowImplementationTypes(Class<?>... workflowImplementationClasses) {
      final Class<?>[] classes = copyClasses(workflowImplementationClasses);
      registrations.add(registrar -> registrar.registerWorkflowImplementationTypes(classes));
      return this;
    }

    public Builder registerWorkflowImplementationTypes(
        WorkflowImplementationOptions options, Class<?>... workflowImplementationClasses) {
      Objects.requireNonNull(options, "options");
      final Class<?>[] classes = copyClasses(workflowImplementationClasses);
      registrations.add(
          registrar -> registrar.registerWorkflowImplementationTypes(options, classes));
      return this;
    }

    /**
     * Registers a dynamic workflow implementation type.
     *
     * <p>Only one dynamic workflow implementation type can be registered per worker.
     */
    public Builder registerDynamicWorkflowImplementationType(
        Class<? extends DynamicWorkflow> workflowImplementationClass) {
      final Class<? extends DynamicWorkflow> implementationClass =
          Objects.requireNonNull(workflowImplementationClass, "workflowImplementationClass");
      registrations.add(
          registrar -> registrar.registerWorkflowImplementationTypes(implementationClass));
      return this;
    }

    /**
     * Registers a dynamic workflow implementation type with custom workflow implementation options.
     *
     * <p>Only one dynamic workflow implementation type can be registered per worker.
     */
    public Builder registerDynamicWorkflowImplementationType(
        WorkflowImplementationOptions options,
        Class<? extends DynamicWorkflow> workflowImplementationClass) {
      Objects.requireNonNull(options, "options");
      final Class<? extends DynamicWorkflow> implementationClass =
          Objects.requireNonNull(workflowImplementationClass, "workflowImplementationClass");
      registrations.add(
          registrar -> registrar.registerWorkflowImplementationTypes(options, implementationClass));
      return this;
    }

    public <R> Builder registerWorkflowImplementationFactory(
        Class<R> workflowInterface, Functions.Func<R> factory) {
      Objects.requireNonNull(workflowInterface, "workflowInterface");
      Objects.requireNonNull(factory, "factory");
      registrations.add(
          registrar -> registrar.registerWorkflowImplementationFactory(workflowInterface, factory));
      return this;
    }

    public <R> Builder registerWorkflowImplementationFactory(
        Class<R> workflowInterface,
        Functions.Func<R> factory,
        WorkflowImplementationOptions options) {
      Objects.requireNonNull(workflowInterface, "workflowInterface");
      Objects.requireNonNull(factory, "factory");
      Objects.requireNonNull(options, "options");
      registrations.add(
          registrar ->
              registrar.registerWorkflowImplementationFactory(workflowInterface, factory, options));
      return this;
    }

    public <R> Builder registerWorkflowImplementationFactory(
        Class<R> workflowInterface,
        Functions.Func1<EncodedValues, R> factory,
        WorkflowImplementationOptions options) {
      Objects.requireNonNull(workflowInterface, "workflowInterface");
      Objects.requireNonNull(factory, "factory");
      Objects.requireNonNull(options, "options");
      registrations.add(
          registrar ->
              registrar.registerWorkflowImplementationFactory(workflowInterface, factory, options));
      return this;
    }

    public Builder registerActivitiesImplementations(Object... activityImplementations) {
      final Object[] implementations =
          copyObjects(activityImplementations, "activityImplementations");
      registrations.add(registrar -> registrar.registerActivitiesImplementations(implementations));
      return this;
    }

    /**
     * Registers a dynamic activity implementation.
     *
     * <p>Only one dynamic activity implementation can be registered per worker.
     */
    public Builder registerDynamicActivityImplementation(DynamicActivity activityImplementation) {
      final DynamicActivity implementation =
          Objects.requireNonNull(activityImplementation, "activityImplementation");
      registrations.add(registrar -> registrar.registerActivitiesImplementations(implementation));
      return this;
    }

    public Builder registerNexusServiceImplementation(Object... nexusServiceImplementations) {
      final Object[] implementations =
          copyObjects(nexusServiceImplementations, "nexusServiceImplementations");
      registrations.add(registrar -> registrar.registerNexusServiceImplementation(implementations));
      return this;
    }

    /**
     * Adds a shutdown hook that runs after the worker has stopped and before service stubs close.
     */
    public Builder addShutdownHook(Runnable hook) {
      shutdownHooks.add(Objects.requireNonNull(hook, "hook"));
      return this;
    }

    public LambdaWorkerOptions build() {
      return new LambdaWorkerOptions(
          workflowServiceStubsOptionsBuilder.build(),
          workflowClientOptionsBuilder.build(),
          workerFactoryOptionsBuilder.build(),
          workerOptionsBuilder.build(),
          registrations,
          shutdownHooks,
          taskQueue,
          gracefulShutdownTimeout,
          shutdownDeadlineBuffer,
          shutdownDeadlineBufferExplicit);
    }
  }

  interface Registration {
    void apply(WorkerRegistrar registrar);
  }

  static final class Materialized {
    final WorkflowServiceStubsOptions serviceStubsOptions;
    final WorkflowClientOptions clientOptions;
    final WorkerFactoryOptions workerFactoryOptions;
    final String taskQueue;
    final WorkerOptions workerOptions;
    final Duration gracefulShutdownTimeout;
    final Duration shutdownDeadlineBuffer;
    final List<Registration> registrations;
    final List<Runnable> shutdownHooks;

    private Materialized(
        WorkflowServiceStubsOptions serviceStubsOptions,
        WorkflowClientOptions clientOptions,
        WorkerFactoryOptions workerFactoryOptions,
        String taskQueue,
        WorkerOptions workerOptions,
        Duration gracefulShutdownTimeout,
        Duration shutdownDeadlineBuffer,
        List<Registration> registrations,
        List<Runnable> shutdownHooks) {
      this.serviceStubsOptions = serviceStubsOptions;
      this.clientOptions = clientOptions;
      this.workerFactoryOptions = workerFactoryOptions;
      this.taskQueue = taskQueue;
      this.workerOptions = workerOptions;
      this.gracefulShutdownTimeout = gracefulShutdownTimeout;
      this.shutdownDeadlineBuffer = shutdownDeadlineBuffer;
      this.registrations = Collections.unmodifiableList(registrations);
      this.shutdownHooks = Collections.unmodifiableList(shutdownHooks);
    }
  }

  static final class Prepared {
    private final WorkflowServiceStubsOptions serviceStubsOptions;
    private final WorkflowClientOptions clientOptions;
    private final WorkerFactoryOptions workerFactoryOptions;
    private final String taskQueue;
    private final WorkerOptions workerOptions;
    private final Duration gracefulShutdownTimeout;
    private final Duration shutdownDeadlineBuffer;
    private final List<Registration> registrations;
    private final List<Runnable> shutdownHooks;

    private Prepared(
        WorkflowServiceStubsOptions serviceStubsOptions,
        WorkflowClientOptions clientOptions,
        WorkerFactoryOptions workerFactoryOptions,
        String taskQueue,
        WorkerOptions workerOptions,
        Duration gracefulShutdownTimeout,
        Duration shutdownDeadlineBuffer,
        List<Registration> registrations,
        List<Runnable> shutdownHooks) {
      this.serviceStubsOptions = serviceStubsOptions;
      this.clientOptions = clientOptions;
      this.workerFactoryOptions = workerFactoryOptions;
      this.taskQueue = taskQueue;
      this.workerOptions = workerOptions;
      this.gracefulShutdownTimeout = gracefulShutdownTimeout;
      this.shutdownDeadlineBuffer = shutdownDeadlineBuffer;
      this.registrations = Collections.unmodifiableList(registrations);
      this.shutdownHooks = Collections.unmodifiableList(shutdownHooks);
    }

    Materialized materialize(String invocationIdentity) {
      WorkflowClientOptions.Builder clientOptionsBuilder =
          WorkflowClientOptions.newBuilder(clientOptions);
      WorkerOptions.Builder workerOptionsBuilder = WorkerOptions.newBuilder(workerOptions);

      if (clientOptions.getIdentity() == null && workerOptions.getIdentity() == null) {
        clientOptionsBuilder.setIdentity(invocationIdentity);
        workerOptionsBuilder.setIdentity(invocationIdentity);
      }

      return new Materialized(
          serviceStubsOptions,
          clientOptionsBuilder.validateAndBuildWithDefaults(),
          workerFactoryOptions,
          taskQueue,
          workerOptionsBuilder.validateAndBuildWithDefaults(),
          gracefulShutdownTimeout,
          shutdownDeadlineBuffer,
          new ArrayList<>(registrations),
          new ArrayList<>(shutdownHooks));
    }
  }
}
