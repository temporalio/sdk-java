package io.temporal.aws.lambda;

import static org.junit.Assert.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.opentelemetry.OpenTelemetryFlushHook;
import io.temporal.opentelemetry.TallyScopeFlushHook;
import java.io.Closeable;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class OtelLambdaWorkerConfigurationHelperTest {
  private static final WorkerDeploymentVersion VERSION =
      new WorkerDeploymentVersion("deployment", "build");

  @Test
  public void defaultsResolveEndpointAndServiceName() {
    assertEquals(
        "http://localhost:4317",
        OtelLambdaWorkerConfigurationHelper.newBuilder(new HashMap<>()).getEndpoint());
    assertEquals(
        "temporal-lambda-worker",
        OtelLambdaWorkerConfigurationHelper.newBuilder(new HashMap<>()).getServiceName());

    Map<String, String> env = new HashMap<>();
    env.put(
        OtelLambdaWorkerConfigurationHelper.OTEL_EXPORTER_OTLP_ENDPOINT, "http://collector:4317");
    env.put(OtelLambdaWorkerConfigurationHelper.AWS_LAMBDA_FUNCTION_NAME, "function-name");
    assertEquals(
        "http://collector:4317", OtelLambdaWorkerConfigurationHelper.newBuilder(env).getEndpoint());
    assertEquals(
        "function-name", OtelLambdaWorkerConfigurationHelper.newBuilder(env).getServiceName());

    env.put(OtelLambdaWorkerConfigurationHelper.OTEL_SERVICE_NAME, "explicit-service");
    assertEquals(
        "explicit-service", OtelLambdaWorkerConfigurationHelper.newBuilder(env).getServiceName());
  }

  @Test
  public void defaultFactoryCreatesXRayTraceIds() {
    OpenTelemetry openTelemetry =
        OtelLambdaWorkerConfigurationHelper.newBuilder(new HashMap<>())
            .setFlushTimeout(Duration.ofMillis(10))
            .createOpenTelemetry();
    long beforeSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());

    assertTrue(openTelemetry instanceof OpenTelemetrySdk);

    OpenTelemetrySdk sdk = (OpenTelemetrySdk) openTelemetry;
    Span span = sdk.getSdkTracerProvider().get("test").spanBuilder("test").startSpan();
    try {
      String traceId = span.getSpanContext().getTraceId();
      long afterSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
      long xrayTimestampSeconds = Long.parseLong(traceId.substring(0, 8), 16);

      assertTrue(xrayTimestampSeconds >= beforeSeconds);
      assertTrue(xrayTimestampSeconds <= afterSeconds);
    } finally {
      sdk.shutdown().join(1, TimeUnit.SECONDS);
      span.end();
    }
  }

  @Test
  public void exporterFactoryReceivesResolvedEndpointServiceNameAndIdGenerator() throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put(
        OtelLambdaWorkerConfigurationHelper.OTEL_EXPORTER_OTLP_ENDPOINT, "http://collector:4317");
    env.put(OtelLambdaWorkerConfigurationHelper.AWS_LAMBDA_FUNCTION_NAME, "function-name");
    RecordingTelemetryFactory factory = new RecordingTelemetryFactory();
    LambdaWorkerOptions.Builder options = LambdaWorkerOptions.newBuilderFromEnvironment(baseEnv());

    OtelLambdaWorkerConfigurationHelper.newBuilder(env).setTelemetryFactory(factory).apply(options);

    assertEquals(1, factory.creates.get());
    assertEquals("http://collector:4317", factory.endpoint);
    assertEquals("function-name", factory.serviceName);
    assertNotNull(factory.idGenerator);
  }

  @Test
  public void customOpenTelemetryBypassesExporterCreation() throws Exception {
    RecordingTelemetryFactory factory = new RecordingTelemetryFactory();
    LambdaWorkerOptions.Builder options = LambdaWorkerOptions.newBuilderFromEnvironment(baseEnv());

    OtelLambdaWorkerConfigurationHelper.newBuilder(new HashMap<>())
        .setTelemetryFactory(factory)
        .setOpenTelemetry(OpenTelemetry.noop())
        .apply(options);

    assertEquals(0, factory.creates.get());
    assertNotNull(options.getWorkflowServiceStubsOptionsBuilder().build().getMetricsScope());
  }

  @Test
  public void metricsScopeAndTracingInterceptorsAreInstalled() throws Exception {
    LambdaWorkerOptions.Builder options = LambdaWorkerOptions.newBuilderFromEnvironment(baseEnv());

    OtelLambdaWorkerConfigurationHelper.configure(
        options, builder -> builder.setOpenTelemetry(OpenTelemetry.noop()).setFlushHook(() -> {}));

    assertNotNull(options.getWorkflowServiceStubsOptionsBuilder().build().getMetricsScope());
    assertEquals(1, options.getWorkflowClientOptionsBuilder().build().getInterceptors().length);
    assertEquals(
        1, options.getWorkerFactoryOptionsBuilder().build().getWorkerInterceptors().length);
  }

  @Test
  public void configureRegistersTallyFlushBeforeOpenTelemetryFlush() throws Exception {
    LambdaWorkerOptions.Builder options = LambdaWorkerOptions.newBuilderFromEnvironment(baseEnv());

    OtelLambdaWorkerConfigurationHelper.configure(
        options, builder -> builder.setOpenTelemetry(OpenTelemetry.noop()));
    options.setTaskQueue("task-queue");

    List<Runnable> hooks = options.build().prepare(VERSION).materialize("identity").shutdownHooks;
    assertEquals(2, hooks.size());
    assertTrue(hooks.get(0) instanceof TallyScopeFlushHook);
    assertTrue(hooks.get(1) instanceof OpenTelemetryFlushHook);
  }

  @Test
  public void flushHookReceivesLambdaCleanupTimeoutAndDoesNotCloseProviders() {
    TimeoutRecordingOpenTelemetry openTelemetry = new TimeoutRecordingOpenTelemetry();
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> {
              options.setTaskQueue("task-queue");
              OtelLambdaWorkerConfigurationHelper.configureFlushHook(
                  options, openTelemetry, Duration.ofSeconds(10));
            },
            runtime,
            duration -> {});

    handler.handleRequest(null, context());

    assertTrue(openTelemetry.tracerProvider.joinTimeoutMillis.get() > 0);
    assertTrue(openTelemetry.tracerProvider.joinTimeoutMillis.get() < 10_000);
    assertTrue(openTelemetry.meterProvider.joinTimeoutMillis.get() > 0);
    assertTrue(openTelemetry.meterProvider.joinTimeoutMillis.get() < 10_000);
    assertEquals(0, openTelemetry.tracerProvider.closes.get());
    assertEquals(0, openTelemetry.meterProvider.closes.get());
  }

  @Test
  public void flushHookRunsOncePerInvocationAndDoesNotCloseProviders() {
    CountingOpenTelemetry openTelemetry = new CountingOpenTelemetry();
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> {
              options.setTaskQueue("task-queue");
              OtelLambdaWorkerConfigurationHelper.configure(
                  options, builder -> builder.setOpenTelemetry(openTelemetry));
            },
            runtime,
            duration -> {});

    handler.handleRequest(null, context());
    handler.handleRequest(null, context());

    assertEquals(2, openTelemetry.tracerProvider.flushes.get());
    assertEquals(2, openTelemetry.meterProvider.flushes.get());
    assertEquals(0, openTelemetry.tracerProvider.closes.get());
    assertEquals(0, openTelemetry.meterProvider.closes.get());
  }

  private Context context() {
    return new TestLambdaContext(20_000);
  }

  private Map<String, String> baseEnv() {
    Map<String, String> env = new HashMap<>();
    env.put(LambdaWorkerOptions.TEMPORAL_CONFIG_FILE, "/nonexistent/temporal.toml");
    return env;
  }

  private RequestHandler<Object, Void> handler(
      java.util.function.Consumer<LambdaWorkerOptions.Builder> configure,
      FakeRuntime runtime,
      LambdaWorker.Sleeper sleeper) {
    try {
      LambdaWorkerOptions.Builder options =
          LambdaWorkerOptions.newBuilderFromEnvironment(baseEnv());
      configure.accept(options);
      return LambdaWorker.newHandler(VERSION, options.build(), runtime, sleeper);
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static final class RecordingTelemetryFactory
      implements OtelLambdaWorkerConfigurationHelper.TelemetryFactory {
    private final AtomicInteger creates = new AtomicInteger();
    private String endpoint;
    private String serviceName;
    private IdGenerator idGenerator;

    @Override
    public OpenTelemetry create(
        String endpoint,
        String serviceName,
        Duration metricsReportInterval,
        Duration flushTimeout,
        IdGenerator idGenerator) {
      creates.incrementAndGet();
      this.endpoint = endpoint;
      this.serviceName = serviceName;
      this.idGenerator = idGenerator;
      return OpenTelemetry.noop();
    }
  }

  private static final class CountingOpenTelemetry implements OpenTelemetry {
    private final CountingTracerProvider tracerProvider = new CountingTracerProvider();
    private final CountingMeterProvider meterProvider = new CountingMeterProvider();

    @Override
    public TracerProvider getTracerProvider() {
      return tracerProvider;
    }

    @Override
    public MeterProvider getMeterProvider() {
      return meterProvider;
    }

    @Override
    public ContextPropagators getPropagators() {
      return ContextPropagators.noop();
    }
  }

  private static final class TimeoutRecordingOpenTelemetry implements OpenTelemetry {
    private final TimeoutRecordingTracerProvider tracerProvider =
        new TimeoutRecordingTracerProvider();
    private final TimeoutRecordingMeterProvider meterProvider = new TimeoutRecordingMeterProvider();

    @Override
    public TracerProvider getTracerProvider() {
      return tracerProvider;
    }

    @Override
    public MeterProvider getMeterProvider() {
      return meterProvider;
    }

    @Override
    public ContextPropagators getPropagators() {
      return ContextPropagators.noop();
    }
  }

  public static final class TimeoutRecordingTracerProvider implements TracerProvider, Closeable {
    private final AtomicLong joinTimeoutMillis = new AtomicLong(-1);
    private final AtomicInteger closes = new AtomicInteger();

    @Override
    public Tracer get(String instrumentationName) {
      return TracerProvider.noop().get(instrumentationName);
    }

    @Override
    public Tracer get(String instrumentationName, String instrumentationVersion) {
      return TracerProvider.noop().get(instrumentationName, instrumentationVersion);
    }

    public TimeoutRecordingResult forceFlush() {
      return new TimeoutRecordingResult(joinTimeoutMillis);
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }

  public static final class TimeoutRecordingMeterProvider implements MeterProvider, Closeable {
    private final AtomicLong joinTimeoutMillis = new AtomicLong(-1);
    private final AtomicInteger closes = new AtomicInteger();

    @Override
    public MeterBuilder meterBuilder(String instrumentationName) {
      return MeterProvider.noop().meterBuilder(instrumentationName);
    }

    public TimeoutRecordingResult forceFlush() {
      return new TimeoutRecordingResult(joinTimeoutMillis);
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }

  public static final class TimeoutRecordingResult {
    private final AtomicLong joinTimeoutMillis;

    private TimeoutRecordingResult(AtomicLong joinTimeoutMillis) {
      this.joinTimeoutMillis = joinTimeoutMillis;
    }

    public TimeoutRecordingResult join(long timeout, TimeUnit unit) {
      joinTimeoutMillis.set(unit.toMillis(timeout));
      return this;
    }
  }

  public static final class CountingTracerProvider implements TracerProvider, Closeable {
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();

    @Override
    public Tracer get(String instrumentationName) {
      return TracerProvider.noop().get(instrumentationName);
    }

    @Override
    public Tracer get(String instrumentationName, String instrumentationVersion) {
      return TracerProvider.noop().get(instrumentationName, instrumentationVersion);
    }

    public CompletableResultCode forceFlush() {
      flushes.incrementAndGet();
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }

  public static final class CountingMeterProvider implements MeterProvider, Closeable {
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();

    @Override
    public MeterBuilder meterBuilder(String instrumentationName) {
      return MeterProvider.noop().meterBuilder(instrumentationName);
    }

    public CompletableResultCode forceFlush() {
      flushes.incrementAndGet();
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }

  private static final class FakeRuntime implements LambdaWorkerRuntime {
    @Override
    public Invocation create(
        io.temporal.serviceclient.WorkflowServiceStubsOptions serviceStubsOptions,
        io.temporal.client.WorkflowClientOptions clientOptions,
        io.temporal.worker.WorkerFactoryOptions workerFactoryOptions,
        String taskQueue,
        io.temporal.worker.WorkerOptions workerOptions) {
      return new Invocation() {
        @Override
        public WorkerRegistrar getWorkerRegistrar() {
          return new NoopRegistrar();
        }

        @Override
        public void start() {}

        @Override
        public void shutdown() {}

        @Override
        public void shutdownNow() {}

        @Override
        public void awaitTermination(java.time.Duration timeout) {}

        @Override
        public boolean isTerminated() {
          return true;
        }

        @Override
        public void closeStubs(java.time.Duration timeout) {}
      };
    }
  }

  private static final class NoopRegistrar implements WorkerRegistrar {
    @Override
    public void registerWorkflowImplementationTypes(Class<?>... workflowImplementationClasses) {}

    @Override
    public void registerWorkflowImplementationTypes(
        io.temporal.worker.WorkflowImplementationOptions options,
        Class<?>... workflowImplementationClasses) {}

    @Override
    public <R> void registerWorkflowImplementationFactory(
        Class<R> workflowInterface, io.temporal.workflow.Functions.Func<R> factory) {}

    @Override
    public <R> void registerWorkflowImplementationFactory(
        Class<R> workflowInterface,
        io.temporal.workflow.Functions.Func1<io.temporal.common.converter.EncodedValues, R> factory,
        io.temporal.worker.WorkflowImplementationOptions options) {}

    @Override
    public <R> void registerWorkflowImplementationFactory(
        Class<R> workflowInterface,
        io.temporal.workflow.Functions.Func<R> factory,
        io.temporal.worker.WorkflowImplementationOptions options) {}

    @Override
    public void registerActivitiesImplementations(Object... activityImplementations) {}

    @Override
    public void registerNexusServiceImplementation(Object... nexusServiceImplementations) {}
  }
}
