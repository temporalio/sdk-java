package io.temporal.aws.lambda;

import static org.junit.Assert.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.temporal.activity.DynamicActivity;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.SimplePlugin;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.common.converter.EncodedValues;
import io.temporal.opentelemetry.TimedShutdownHook;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.DynamicWorkflow;
import io.temporal.workflow.Functions;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LambdaWorkerLifecycleTest {
  private static final WorkerDeploymentVersion VERSION =
      new WorkerDeploymentVersion("deployment", "build");

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void invocationLifecycleRunsInOrder() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options ->
                options
                    .setTaskQueue("task-queue")
                    .registerWorkflowImplementationTypes(TestWorkflowImpl.class)
                    .registerActivitiesImplementations(new Object())
                    .registerNexusServiceImplementation(new Object())
                    .addShutdownHook(() -> runtime.events.add("hook-1"))
                    .addShutdownHook(() -> runtime.events.add("hook-2")),
            runtime,
            duration -> runtime.events.add("sleep:" + duration.toMillis()));

    handler.handleRequest(null, context(20_000));

    assertEquals(
        events(
            "create",
            "registerWorkflowTypes:1",
            "registerActivities:1",
            "registerNexus:1",
            "start",
            "sleep:13000",
            "shutdown",
            "await:5000",
            "hook-1",
            "hook-2",
            "close:2000"),
        runtime.events);
  }

  @Test
  public void dynamicRegistrationsReplayBeforeWorkerStart() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options ->
                options
                    .setTaskQueue("task-queue")
                    .registerDynamicWorkflowImplementationType(TestDynamicWorkflow.class)
                    .registerDynamicWorkflowImplementationType(
                        WorkflowImplementationOptions.getDefaultInstance(),
                        TestDynamicWorkflowWithOptions.class)
                    .registerDynamicActivityImplementation(new TestDynamicActivity()),
            runtime);

    handler.handleRequest(null, context(20_000));

    assertEquals(
        events(
            "create",
            "registerWorkflowTypes:1",
            "registerWorkflowTypesWithOptions:1",
            "registerActivities:1",
            "start",
            "shutdown",
            "await:5000",
            "close:2000"),
        runtime.events);
  }

  @Test
  public void configureRunsOnceAndRuntimeIsCreatedOncePerInvocation() {
    AtomicInteger configureCount = new AtomicInteger();
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> {
              configureCount.incrementAndGet();
              options.setTaskQueue("task-queue");
            },
            runtime);

    assertEquals(1, configureCount.get());

    handler.handleRequest(null, context(20_000, "request-1", "function-arn-1"));
    assertEquals("request-1@function-arn-1", runtime.clientOptions.getIdentity());
    assertEquals("request-1@function-arn-1", runtime.workerOptions.getIdentity());

    handler.handleRequest(null, context(20_000, "request-2", "function-arn-2"));
    assertEquals("request-2@function-arn-2", runtime.clientOptions.getIdentity());
    assertEquals("request-2@function-arn-2", runtime.workerOptions.getIdentity());

    assertEquals(1, configureCount.get());
    assertEquals(2, runtime.createCount);
  }

  @Test
  public void perInvocationConfigureRunsOncePerInvocation() {
    AtomicInteger coldStartConfigureCount = new AtomicInteger();
    AtomicInteger invocationConfigureCount = new AtomicInteger();
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> {
              coldStartConfigureCount.incrementAndGet();
              options.setTaskQueue("task-queue");
            },
            (options, context) -> {
              invocationConfigureCount.incrementAndGet();
              options.getWorkflowClientOptionsBuilder().setNamespace(context.getAwsRequestId());
            },
            runtime);

    assertEquals(1, coldStartConfigureCount.get());

    handler.handleRequest(null, context(20_000, "request-1", "function-arn-1"));
    assertEquals("request-1", runtime.clientOptions.getNamespace());

    handler.handleRequest(null, context(20_000, "request-2", "function-arn-2"));
    assertEquals("request-2", runtime.clientOptions.getNamespace());

    assertEquals(1, coldStartConfigureCount.get());
    assertEquals(2, invocationConfigureCount.get());
    assertEquals(2, runtime.createCount);
  }

  @Test
  public void perInvocationConfigureCanSupplyTaskQueue() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> {},
            (options, context) -> options.setTaskQueue(context.getAwsRequestId()),
            runtime);

    handler.handleRequest(null, context(20_000, "request-task-queue", "function-arn"));

    assertEquals("request-task-queue", runtime.taskQueue);
    assertEquals(1, runtime.createCount);
  }

  @Test
  public void missingTaskQueueWithPerInvocationConfigureFailsDuringInvocation() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(options -> {}, (options, context) -> {}, runtime);

    assertThrows(IllegalStateException.class, () -> handler.handleRequest(null, context(20_000)));

    assertEquals(0, runtime.createCount);
  }

  @Test
  public void perInvocationShutdownHooksDoNotAccumulateAcrossInvocations() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> options.setTaskQueue("task-queue"),
            (options, context) ->
                options.addShutdownHook(
                    () -> runtime.events.add("hook:" + context.getAwsRequestId())),
            runtime);

    handler.handleRequest(null, context(20_000, "request-1", "function-arn"));
    handler.handleRequest(null, context(20_000, "request-2", "function-arn"));

    assertEquals(1, Collections.frequency(runtime.events, "hook:request-1"));
    assertEquals(1, Collections.frequency(runtime.events, "hook:request-2"));
  }

  @Test
  public void perInvocationShutdownHooksRunWhenConfigureThrows() {
    FakeRuntime runtime = new FakeRuntime();
    FakeMonotonicClock clock = new FakeMonotonicClock();
    AtomicReference<Duration> hookTimeout = new AtomicReference<>();
    RequestHandler<Object, Void> handler =
        handler(
            options -> {},
            (options, context) -> {
              options.setTaskQueue("task-queue");
              options.addShutdownHook(
                  new TimedShutdownHook() {
                    @Override
                    public void run() {
                      run(Duration.ZERO);
                    }

                    @Override
                    public void run(Duration timeout) {
                      hookTimeout.set(timeout);
                      runtime.events.add("cleanup");
                    }
                  });
              throw new RuntimeException("configure failed");
            },
            runtime,
            duration -> {},
            clock);

    RuntimeException e =
        assertThrows(RuntimeException.class, () -> handler.handleRequest(null, context(20_000)));

    assertEquals("configure failed", e.getMessage());
    assertEquals(0, runtime.createCount);
    assertEquals(Duration.ofSeconds(2), hookTimeout.get());
    assertEquals(events("cleanup"), runtime.events);
  }

  @Test
  public void perInvocationShutdownHooksRunWhenFinalValidationFails() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> {},
            (options, context) -> options.addShutdownHook(() -> runtime.events.add("cleanup")),
            runtime);

    assertThrows(IllegalStateException.class, () -> handler.handleRequest(null, context(20_000)));

    assertEquals(0, runtime.createCount);
    assertEquals(events("cleanup"), runtime.events);
  }

  @Test
  public void newHandlerSupportsPerInvocationConfigure() throws IOException {
    FakeRuntime runtime = new FakeRuntime();
    LambdaWorkerOptions options = LambdaWorkerOptions.newBuilderFromEnvironment(baseEnv()).build();
    RequestHandler<Object, Void> handler =
        LambdaWorker.newHandler(
            VERSION,
            options,
            (builder, context) -> {
              builder.setTaskQueue("task-queue");
              builder.getWorkerOptionsBuilder().setIdentity("worker-" + context.getAwsRequestId());
            },
            runtime,
            duration -> {},
            new FakeMonotonicClock());

    handler.handleRequest(null, context(20_000, "request-1", "function-arn"));

    assertEquals("task-queue", runtime.taskQueue);
    assertEquals("worker-request-1", runtime.workerOptions.getIdentity());
    assertEquals("worker-request-1", runtime.clientOptions.getIdentity());
  }

  @Test
  public void missingTaskQueueFailsDuringHandlerConstruction() {
    FakeRuntime runtime = new FakeRuntime();

    assertThrows(IllegalStateException.class, () -> handler(options -> {}, runtime));

    assertEquals(0, runtime.createCount);
  }

  @Test
  public void envconfigValuesAreVisibleToConfigureBeforeInvocation() throws IOException {
    File config = temporaryFolder.newFile("temporal.toml");
    Files.write(
        config.toPath(),
        "[profile.default]\nnamespace = \"configured\"\n".getBytes(StandardCharsets.UTF_8));
    Map<String, String> env = new HashMap<>();
    env.put(LambdaWorkerOptions.TEMPORAL_CONFIG_FILE, config.getAbsolutePath());
    AtomicReference<String> namespaceInConfigure = new AtomicReference<>();
    FakeRuntime runtime = new FakeRuntime();

    RequestHandler<Object, Void> handler =
        handler(
            env,
            options -> {
              namespaceInConfigure.set(
                  options.getWorkflowClientOptionsBuilder().build().getNamespace());
              options.setTaskQueue("task-queue");
            },
            runtime,
            duration -> {});

    assertEquals("configured", namespaceInConfigure.get());

    handler.handleRequest(null, context(20_000));

    assertEquals("configured", runtime.clientOptions.getNamespace());
  }

  @Test
  public void identityUsesAwsRequestIdAndFunctionArn() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(options -> options.setTaskQueue("task-queue"), runtime);

    handler.handleRequest(null, context(20_000));

    assertEquals("request-id@function-arn", runtime.clientOptions.getIdentity());
    assertEquals("request-id@function-arn", runtime.workerOptions.getIdentity());
  }

  @Test
  public void userProvidedClientIdentityIsPreserved() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> {
              options.setTaskQueue("task-queue");
              options.getWorkflowClientOptionsBuilder().setIdentity("custom-client");
            },
            runtime);

    handler.handleRequest(null, context(20_000));

    assertEquals("custom-client", runtime.clientOptions.getIdentity());
    assertEquals("custom-client", runtime.workerOptions.getIdentity());
  }

  @Test
  public void userProvidedWorkerIdentityIsPreserved() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> {
              options.setTaskQueue("task-queue");
              options.getWorkerOptionsBuilder().setIdentity("custom-worker");
            },
            runtime);

    handler.handleRequest(null, context(20_000));

    assertEquals("custom-worker", runtime.workerOptions.getIdentity());
    assertEquals("custom-worker", runtime.clientOptions.getIdentity());
  }

  @Test
  public void insufficientRemainingTimeThrowsBeforeRuntimeCreation() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(options -> options.setTaskQueue("task-queue"), runtime);

    assertThrows(IllegalStateException.class, () -> handler.handleRequest(null, context(8_000)));

    assertEquals(0, runtime.createCount);
  }

  @Test
  public void lowRemainingTimeStillStartsAndSleepsUntilShutdownBuffer() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> options.setTaskQueue("task-queue"),
            runtime,
            duration -> runtime.events.add("sleep:" + duration.toMillis()));

    handler.handleRequest(null, context(10_500));

    assertTrue(runtime.events.contains("start"));
    assertTrue(runtime.events.contains("sleep:3500"));
  }

  @Test
  public void shutdownHookErrorsDoNotSkipLaterHooks() {
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options ->
                options
                    .setTaskQueue("task-queue")
                    .addShutdownHook(
                        () -> {
                          runtime.events.add("hook-1");
                          throw new RuntimeException("hook failed");
                        })
                    .addShutdownHook(() -> runtime.events.add("hook-2")),
            runtime);

    handler.handleRequest(null, context(20_000));

    assertTrue(runtime.events.contains("hook-1"));
    assertTrue(runtime.events.contains("hook-2"));
    assertEquals("close:2000", runtime.events.get(runtime.events.size() - 1));
  }

  @Test
  public void shutdownHooksAndStubsShareCleanupDeadline() {
    FakeRuntime runtime = new FakeRuntime();
    FakeMonotonicClock clock = new FakeMonotonicClock();
    AtomicReference<Duration> hookTimeout = new AtomicReference<>();
    RequestHandler<Object, Void> handler =
        handler(
            options ->
                options
                    .setTaskQueue("task-queue")
                    .addShutdownHook(
                        new TimedShutdownHook() {
                          @Override
                          public void run() {
                            run(Duration.ZERO);
                          }

                          @Override
                          public void run(Duration timeout) {
                            hookTimeout.set(timeout);
                            clock.advance(Duration.ofMillis(1500));
                          }
                        }),
            runtime,
            duration -> {},
            clock);

    handler.handleRequest(null, context(20_000));

    assertEquals(Duration.ofSeconds(2), hookTimeout.get());
    assertEquals("close:500", runtime.events.get(runtime.events.size() - 1));
  }

  @Test
  public void workerShutdownEscalatesAfterGracefulTimeoutAndSharesCleanupDeadline() {
    FakeMonotonicClock clock = new FakeMonotonicClock();
    FakeRuntime runtime = new FakeRuntime(false, true, clock, Duration.ofMillis(1500));
    RequestHandler<Object, Void> handler =
        handler(options -> options.setTaskQueue("task-queue"), runtime, duration -> {}, clock);

    handler.handleRequest(null, context(20_000));

    assertEquals(
        events(
            "create", "start", "shutdown", "await:5000", "shutdownNow", "await:2000", "close:500"),
        runtime.events);
  }

  @Test
  public void workerShutdownEscalatesWhenGracefulAwaitThrows() {
    FakeMonotonicClock clock = new FakeMonotonicClock();
    FakeRuntime runtime = new FakeRuntime(false, true, clock, Duration.ZERO, true);
    RequestHandler<Object, Void> handler =
        handler(options -> options.setTaskQueue("task-queue"), runtime, duration -> {}, clock);

    handler.handleRequest(null, context(20_000));

    assertEquals(
        events(
            "create", "start", "shutdown", "await:5000", "shutdownNow", "await:2000", "close:2000"),
        runtime.events);
  }

  @Test
  public void defaultRuntimeShutsDownFactoryWhenWorkerCreationFails() {
    AtomicInteger factoryShutdowns = new AtomicInteger();
    DefaultLambdaWorkerRuntime runtime = new DefaultLambdaWorkerRuntime();

    WorkerFactoryOptions factoryOptions =
        WorkerFactoryOptions.newBuilder()
            .setPlugins(
                new SimplePlugin("failing-worker-initializer") {
                  @Override
                  public void configureWorker(String taskQueue, WorkerOptions.Builder builder) {
                    throw new RuntimeException("worker configuration failed");
                  }

                  @Override
                  public void shutdownWorkerFactory(
                      WorkerFactory factory, Consumer<WorkerFactory> next) {
                    factoryShutdowns.incrementAndGet();
                    next.accept(factory);
                  }
                })
            .build();

    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () ->
                runtime.create(
                    WorkflowServiceStubsOptions.newBuilder()
                        .setRpcTimeout(Duration.ofMillis(10))
                        .setSystemInfoTimeout(Duration.ofMillis(10))
                        .build(),
                    WorkflowClientOptions.newBuilder().build(),
                    factoryOptions,
                    "task-queue",
                    WorkerOptions.newBuilder().build()));

    assertEquals("worker configuration failed", e.getMessage());
    assertEquals(1, factoryShutdowns.get());
  }

  private RequestHandler<Object, Void> handler(
      java.util.function.Consumer<LambdaWorkerOptions.Builder> configure, FakeRuntime runtime) {
    return handler(configure, runtime, duration -> {});
  }

  private RequestHandler<Object, Void> handler(
      java.util.function.Consumer<LambdaWorkerOptions.Builder> configure,
      LambdaWorker.InvocationConfigurator invocationConfigure,
      FakeRuntime runtime) {
    return handler(configure, invocationConfigure, runtime, duration -> {});
  }

  private RequestHandler<Object, Void> handler(
      java.util.function.Consumer<LambdaWorkerOptions.Builder> configure,
      FakeRuntime runtime,
      LambdaWorker.Sleeper sleeper) {
    return handler(baseEnv(), configure, runtime, sleeper, new FakeMonotonicClock());
  }

  private RequestHandler<Object, Void> handler(
      java.util.function.Consumer<LambdaWorkerOptions.Builder> configure,
      LambdaWorker.InvocationConfigurator invocationConfigure,
      FakeRuntime runtime,
      LambdaWorker.Sleeper sleeper) {
    return handler(
        baseEnv(), configure, invocationConfigure, runtime, sleeper, new FakeMonotonicClock());
  }

  private RequestHandler<Object, Void> handler(
      java.util.function.Consumer<LambdaWorkerOptions.Builder> configure,
      FakeRuntime runtime,
      LambdaWorker.Sleeper sleeper,
      LambdaWorker.MonotonicClock clock) {
    return handler(baseEnv(), configure, runtime, sleeper, clock);
  }

  private RequestHandler<Object, Void> handler(
      java.util.function.Consumer<LambdaWorkerOptions.Builder> configure,
      LambdaWorker.InvocationConfigurator invocationConfigure,
      FakeRuntime runtime,
      LambdaWorker.Sleeper sleeper,
      LambdaWorker.MonotonicClock clock) {
    return handler(baseEnv(), configure, invocationConfigure, runtime, sleeper, clock);
  }

  private RequestHandler<Object, Void> handler(
      Map<String, String> env,
      java.util.function.Consumer<LambdaWorkerOptions.Builder> configure,
      FakeRuntime runtime,
      LambdaWorker.Sleeper sleeper) {
    return handler(env, configure, runtime, sleeper, new FakeMonotonicClock());
  }

  private RequestHandler<Object, Void> handler(
      Map<String, String> env,
      java.util.function.Consumer<LambdaWorkerOptions.Builder> configure,
      FakeRuntime runtime,
      LambdaWorker.Sleeper sleeper,
      LambdaWorker.MonotonicClock clock) {
    try {
      LambdaWorkerOptions.Builder options = LambdaWorkerOptions.newBuilderFromEnvironment(env);
      configure.accept(options);
      return LambdaWorker.newHandler(VERSION, options.build(), runtime, sleeper, clock);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private RequestHandler<Object, Void> handler(
      Map<String, String> env,
      java.util.function.Consumer<LambdaWorkerOptions.Builder> configure,
      LambdaWorker.InvocationConfigurator invocationConfigure,
      FakeRuntime runtime,
      LambdaWorker.Sleeper sleeper,
      LambdaWorker.MonotonicClock clock) {
    try {
      LambdaWorkerOptions.Builder options = LambdaWorkerOptions.newBuilderFromEnvironment(env);
      configure.accept(options);
      return LambdaWorker.newHandler(
          VERSION, options.build(), invocationConfigure, runtime, sleeper, clock);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Context context(int remainingTimeMillis) {
    return new TestLambdaContext(remainingTimeMillis);
  }

  private Context context(int remainingTimeMillis, String awsRequestId, String invokedFunctionArn) {
    return new TestLambdaContext(remainingTimeMillis, awsRequestId, invokedFunctionArn);
  }

  private Map<String, String> baseEnv() {
    Map<String, String> env = new HashMap<>();
    env.put(LambdaWorkerOptions.TEMPORAL_CONFIG_FILE, "/nonexistent/temporal.toml");
    return env;
  }

  private List<String> events(String... events) {
    List<String> result = new ArrayList<>();
    for (String event : events) {
      result.add(event);
    }
    return result;
  }

  private static final class FakeMonotonicClock implements LambdaWorker.MonotonicClock {
    private long nowNanos;

    @Override
    public long nanoTime() {
      return nowNanos;
    }

    private void advance(Duration duration) {
      nowNanos += duration.toNanos();
    }
  }

  private static final class FakeRuntime implements LambdaWorkerRuntime {
    private final List<String> events = new ArrayList<>();
    private final boolean gracefulTerminates;
    private final boolean forcedTerminates;
    private final FakeMonotonicClock clock;
    private final Duration forcedAwaitDuration;
    private final boolean gracefulAwaitThrows;
    private int createCount;
    private WorkflowClientOptions clientOptions;
    private WorkerOptions workerOptions;
    private String taskQueue;

    private FakeRuntime() {
      this(true, true, null, Duration.ZERO, false);
    }

    private FakeRuntime(
        boolean gracefulTerminates,
        boolean forcedTerminates,
        FakeMonotonicClock clock,
        Duration forcedAwaitDuration) {
      this(gracefulTerminates, forcedTerminates, clock, forcedAwaitDuration, false);
    }

    private FakeRuntime(
        boolean gracefulTerminates,
        boolean forcedTerminates,
        FakeMonotonicClock clock,
        Duration forcedAwaitDuration,
        boolean gracefulAwaitThrows) {
      this.gracefulTerminates = gracefulTerminates;
      this.forcedTerminates = forcedTerminates;
      this.clock = clock;
      this.forcedAwaitDuration = forcedAwaitDuration;
      this.gracefulAwaitThrows = gracefulAwaitThrows;
    }

    @Override
    public Invocation create(
        WorkflowServiceStubsOptions serviceStubsOptions,
        WorkflowClientOptions clientOptions,
        WorkerFactoryOptions workerFactoryOptions,
        String taskQueue,
        WorkerOptions workerOptions) {
      createCount++;
      events.add("create");
      this.clientOptions = clientOptions;
      this.workerOptions = workerOptions;
      this.taskQueue = taskQueue;
      return new FakeInvocation(
          events,
          gracefulTerminates,
          forcedTerminates,
          clock,
          forcedAwaitDuration,
          gracefulAwaitThrows);
    }
  }

  private static final class FakeInvocation implements LambdaWorkerRuntime.Invocation {
    private final List<String> events;
    private final WorkerRegistrar registrar;
    private final boolean gracefulTerminates;
    private final boolean forcedTerminates;
    private final FakeMonotonicClock clock;
    private final Duration forcedAwaitDuration;
    private final boolean gracefulAwaitThrows;
    private boolean terminated;
    private int awaitCount;

    private FakeInvocation(
        List<String> events,
        boolean gracefulTerminates,
        boolean forcedTerminates,
        FakeMonotonicClock clock,
        Duration forcedAwaitDuration,
        boolean gracefulAwaitThrows) {
      this.events = events;
      this.registrar = new FakeWorkerRegistrar(events);
      this.gracefulTerminates = gracefulTerminates;
      this.forcedTerminates = forcedTerminates;
      this.clock = clock;
      this.forcedAwaitDuration = forcedAwaitDuration;
      this.gracefulAwaitThrows = gracefulAwaitThrows;
    }

    @Override
    public WorkerRegistrar getWorkerRegistrar() {
      return registrar;
    }

    @Override
    public void start() {
      events.add("start");
    }

    @Override
    public void shutdown() {
      events.add("shutdown");
      terminated = false;
    }

    @Override
    public void shutdownNow() {
      events.add("shutdownNow");
    }

    @Override
    public void awaitTermination(Duration timeout) {
      events.add("await:" + timeout.toMillis());
      awaitCount++;
      if (awaitCount == 1 && gracefulAwaitThrows) {
        throw new RuntimeException("await failed");
      }
      if (awaitCount == 1 && gracefulTerminates) {
        terminated = true;
      } else if (awaitCount > 1) {
        if (clock != null) {
          clock.advance(forcedAwaitDuration);
        }
        terminated = forcedTerminates;
      }
    }

    @Override
    public boolean isTerminated() {
      return terminated;
    }

    @Override
    public void closeStubs(Duration timeout) {
      events.add("close:" + timeout.toMillis());
    }
  }

  private static final class FakeWorkerRegistrar implements WorkerRegistrar {
    private final List<String> events;

    private FakeWorkerRegistrar(List<String> events) {
      this.events = events;
    }

    @Override
    public void registerWorkflowImplementationTypes(Class<?>... workflowImplementationClasses) {
      events.add("registerWorkflowTypes:" + workflowImplementationClasses.length);
    }

    @Override
    public void registerWorkflowImplementationTypes(
        WorkflowImplementationOptions options, Class<?>... workflowImplementationClasses) {
      events.add("registerWorkflowTypesWithOptions:" + workflowImplementationClasses.length);
    }

    @Override
    public <R> void registerWorkflowImplementationFactory(
        Class<R> workflowInterface, Functions.Func<R> factory) {
      events.add("registerWorkflowFactory");
    }

    @Override
    public <R> void registerWorkflowImplementationFactory(
        Class<R> workflowInterface,
        Functions.Func1<io.temporal.common.converter.EncodedValues, R> factory,
        WorkflowImplementationOptions options) {
      events.add("registerWorkflowFactoryWithArgs");
    }

    @Override
    public <R> void registerWorkflowImplementationFactory(
        Class<R> workflowInterface,
        Functions.Func<R> factory,
        WorkflowImplementationOptions options) {
      events.add("registerWorkflowFactoryWithOptions");
    }

    @Override
    public void registerActivitiesImplementations(Object... activityImplementations) {
      events.add("registerActivities:" + activityImplementations.length);
    }

    @Override
    public void registerNexusServiceImplementation(Object... nexusServiceImplementations) {
      events.add("registerNexus:" + nexusServiceImplementations.length);
    }
  }

  private static final class TestWorkflowImpl {}

  private static final class TestDynamicWorkflow implements DynamicWorkflow {
    @Override
    public Object execute(EncodedValues args) {
      return null;
    }
  }

  private static final class TestDynamicWorkflowWithOptions implements DynamicWorkflow {
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
