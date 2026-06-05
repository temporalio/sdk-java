package io.temporal.aws.lambda;

import static org.junit.Assert.*;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.uber.m3.tally.Capabilities;
import com.uber.m3.tally.CapableOf;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.temporal.common.WorkerDeploymentVersion;
import java.io.Closeable;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class OtelLambdaWorkerTest {
  private static final WorkerDeploymentVersion VERSION =
      new WorkerDeploymentVersion("deployment", "build");

  @Test
  public void defaultsResolveEndpointAndServiceName() {
    assertEquals(
        "http://localhost:4317", OtelLambdaWorker.newBuilder(new HashMap<>()).getEndpoint());
    assertEquals(
        "temporal-lambda-worker", OtelLambdaWorker.newBuilder(new HashMap<>()).getServiceName());

    Map<String, String> env = new HashMap<>();
    env.put(OtelLambdaWorker.OTEL_EXPORTER_OTLP_ENDPOINT, "http://collector:4317");
    env.put(OtelLambdaWorker.AWS_LAMBDA_FUNCTION_NAME, "function-name");
    assertEquals("http://collector:4317", OtelLambdaWorker.newBuilder(env).getEndpoint());
    assertEquals("function-name", OtelLambdaWorker.newBuilder(env).getServiceName());

    env.put(OtelLambdaWorker.OTEL_SERVICE_NAME, "explicit-service");
    assertEquals("explicit-service", OtelLambdaWorker.newBuilder(env).getServiceName());
  }

  @Test
  public void defaultFactoryCreatesExporterBackedSdkProviders() {
    OpenTelemetry openTelemetry =
        OtelLambdaWorker.newBuilder(new HashMap<>()).createOpenTelemetry();

    assertTrue(openTelemetry instanceof OpenTelemetrySdk);

    OpenTelemetrySdk sdk = (OpenTelemetrySdk) openTelemetry;
    assertNotNull(sdk.getSdkTracerProvider());
    assertNotNull(sdk.getSdkMeterProvider());
    sdk.shutdown().join(1, TimeUnit.SECONDS);
  }

  @Test
  public void defaultFactoryCreatesXRayTraceIds() {
    OpenTelemetry openTelemetry =
        OtelLambdaWorker.newBuilder(new HashMap<>())
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
  public void exporterFactoryReceivesResolvedEndpointAndServiceName() throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put(OtelLambdaWorker.OTEL_EXPORTER_OTLP_ENDPOINT, "http://collector:4317");
    env.put(OtelLambdaWorker.AWS_LAMBDA_FUNCTION_NAME, "function-name");
    RecordingTelemetryFactory factory = new RecordingTelemetryFactory();
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());

    OtelLambdaWorker.newBuilder(env).setTelemetryFactory(factory).apply(options);

    assertEquals(1, factory.creates.get());
    assertEquals("http://collector:4317", factory.endpoint);
    assertEquals("function-name", factory.serviceName);
  }

  @Test
  public void customEndpointAndServiceNameAreUsedByExporterFactory() throws Exception {
    RecordingTelemetryFactory factory = new RecordingTelemetryFactory();
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());

    OtelLambdaWorker.newBuilder(new HashMap<>())
        .setTelemetryFactory(factory)
        .setEndpoint("http://custom-collector:4317")
        .setServiceName("custom-service")
        .setMetricsReportInterval(Duration.ofSeconds(3))
        .setFlushTimeout(Duration.ofSeconds(4))
        .apply(options);

    assertEquals(1, factory.creates.get());
    assertEquals("http://custom-collector:4317", factory.endpoint);
    assertEquals("custom-service", factory.serviceName);
    assertEquals(Duration.ofSeconds(3), factory.metricsReportInterval);
    assertEquals(Duration.ofSeconds(4), factory.flushTimeout);
  }

  @Test
  public void metricsScopeAndTracingInterceptorsAreInstalled() throws Exception {
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());

    OtelLambdaWorker.configure(
        options, builder -> builder.setOpenTelemetry(OpenTelemetry.noop()).setFlushHook(() -> {}));

    assertNotNull(options.getWorkflowServiceStubsOptionsBuilder().build().getMetricsScope());
    assertEquals(1, options.getWorkflowClientOptionsBuilder().build().getInterceptors().length);
    assertEquals(
        1, options.getWorkerFactoryOptionsBuilder().build().getWorkerInterceptors().length);
  }

  @Test
  public void configureRegistersTallyFlushBeforeOpenTelemetryFlush() throws Exception {
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());

    OtelLambdaWorker.configure(options, builder -> builder.setOpenTelemetry(OpenTelemetry.noop()));
    options.setTaskQueue("task-queue");

    List<Runnable> hooks = options.prepare(VERSION).materialize("identity").shutdownHooks;
    assertEquals(2, hooks.size());
    assertTrue(hooks.get(0) instanceof TallyScopeFlushHook);
    assertTrue(hooks.get(1) instanceof OpenTelemetryFlushHook);
  }

  @Test
  public void metricsOnlyInstallsScopeWithoutTracingInterceptors() throws Exception {
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());

    OtelLambdaWorker.configureMetrics(options, OpenTelemetry.noop());

    assertNotNull(options.getWorkflowServiceStubsOptionsBuilder().build().getMetricsScope());
    assertEquals(0, clientInterceptorCount(options));
    assertEquals(0, workerInterceptorCount(options));
  }

  @Test
  public void tallyScopeFlushHookReportsBufferedMetricsWithoutClosingScope() throws Exception {
    RecordingStatsReporter reporter = new RecordingStatsReporter();
    Scope scope =
        new RootScopeBuilder().reporter(reporter).reportEvery(com.uber.m3.util.Duration.ofHours(1));
    try {
      scope.counter("buffered-counter").inc(1);

      new TallyScopeFlushHook(scope).run();

      assertTrue(reporter.counterReports.get() >= 1);
      assertTrue(reporter.flushes.get() >= 1);
      assertEquals(0, reporter.closes.get());
    } finally {
      scope.close();
    }
  }

  @Test
  public void tracingOnlyInstallsInterceptorsWithoutMetricsScope() throws Exception {
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());

    OtelLambdaWorker.configureTracing(options, OpenTelemetry.noop());

    assertNull(options.getWorkflowServiceStubsOptionsBuilder().build().getMetricsScope());
    assertEquals(1, clientInterceptorCount(options));
    assertEquals(1, workerInterceptorCount(options));
  }

  @Test
  public void flushHookOnlyRunsOncePerInvocationAndDoesNotCloseProviders() {
    CountingOpenTelemetry openTelemetry = new CountingOpenTelemetry();
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> {
              options.setTaskQueue("task-queue");
              OtelLambdaWorker.configureFlushHook(options, openTelemetry, Duration.ofSeconds(1));
              assertNull(options.getWorkflowServiceStubsOptionsBuilder().build().getMetricsScope());
              assertEquals(0, clientInterceptorCount(options));
              assertEquals(0, workerInterceptorCount(options));
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

  @Test
  public void flushHookUsesSmallerConfiguredAndLambdaTimeout() {
    TimeoutRecordingOpenTelemetry openTelemetry = new TimeoutRecordingOpenTelemetry();

    new OpenTelemetryFlushHook(openTelemetry, Duration.ofMillis(250)).run(Duration.ofSeconds(2));

    assertEquals(250, openTelemetry.tracerProvider.joinTimeoutMillis.get());
    assertEquals(250, openTelemetry.meterProvider.joinTimeoutMillis.get());

    openTelemetry = new TimeoutRecordingOpenTelemetry();

    new OpenTelemetryFlushHook(openTelemetry, Duration.ofSeconds(2)).run(Duration.ofMillis(125));

    assertEquals(125, openTelemetry.tracerProvider.joinTimeoutMillis.get());
    assertEquals(125, openTelemetry.meterProvider.joinTimeoutMillis.get());
  }

  @Test
  public void customOpenTelemetryBypassesExporterCreation() throws Exception {
    RecordingTelemetryFactory factory = new RecordingTelemetryFactory();
    LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());

    OtelLambdaWorker.newBuilder(new HashMap<>())
        .setTelemetryFactory(factory)
        .setOpenTelemetry(OpenTelemetry.noop())
        .apply(options);

    assertEquals(0, factory.creates.get());
    assertNotNull(options.getWorkflowServiceStubsOptionsBuilder().build().getMetricsScope());
  }

  @Test
  public void flushHookRunsOncePerInvocationAndDoesNotCloseProviders() {
    CountingOpenTelemetry openTelemetry = new CountingOpenTelemetry();
    FakeRuntime runtime = new FakeRuntime();
    RequestHandler<Object, Void> handler =
        handler(
            options -> {
              options.setTaskQueue("task-queue");
              OtelLambdaWorker.configure(
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
      java.util.function.Consumer<LambdaWorkerOptions> configure,
      FakeRuntime runtime,
      LambdaWorker.Sleeper sleeper) {
    try {
      LambdaWorkerOptions options = LambdaWorkerOptions.fromEnvironment(baseEnv());
      configure.accept(options);
      return LambdaWorker.newHandler(VERSION, options, runtime, sleeper);
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  private int clientInterceptorCount(LambdaWorkerOptions options) {
    io.temporal.common.interceptors.WorkflowClientInterceptor[] interceptors =
        options.getWorkflowClientOptionsBuilder().build().getInterceptors();
    return interceptors == null ? 0 : interceptors.length;
  }

  private int workerInterceptorCount(LambdaWorkerOptions options) {
    io.temporal.common.interceptors.WorkerInterceptor[] interceptors =
        options.getWorkerFactoryOptionsBuilder().build().getWorkerInterceptors();
    return interceptors == null ? 0 : interceptors.length;
  }

  private static final class RecordingTelemetryFactory
      implements OtelLambdaWorker.TelemetryFactory {
    private final AtomicInteger creates = new AtomicInteger();
    private String endpoint;
    private String serviceName;
    private Duration metricsReportInterval;
    private Duration flushTimeout;

    @Override
    public OpenTelemetry create(
        String endpoint,
        String serviceName,
        Duration metricsReportInterval,
        Duration flushTimeout) {
      creates.incrementAndGet();
      this.endpoint = endpoint;
      this.serviceName = serviceName;
      this.metricsReportInterval = metricsReportInterval;
      this.flushTimeout = flushTimeout;
      return OpenTelemetry.noop();
    }
  }

  @SuppressWarnings("deprecation")
  private static final class RecordingStatsReporter implements StatsReporter {
    private final AtomicInteger counterReports = new AtomicInteger();
    private final AtomicInteger gaugeReports = new AtomicInteger();
    private final AtomicInteger timerReports = new AtomicInteger();
    private final AtomicInteger histogramReports = new AtomicInteger();
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();

    @Override
    public void reportCounter(String name, Map<String, String> tags, long value) {
      counterReports.incrementAndGet();
    }

    @Override
    public void reportGauge(String name, Map<String, String> tags, double value) {
      gaugeReports.incrementAndGet();
    }

    @Override
    public void reportTimer(
        String name, Map<String, String> tags, com.uber.m3.util.Duration interval) {
      timerReports.incrementAndGet();
    }

    @Override
    public void reportHistogramValueSamples(
        String name,
        Map<String, String> tags,
        com.uber.m3.tally.Buckets buckets,
        double bucketLowerBound,
        double bucketUpperBound,
        long samples) {
      histogramReports.incrementAndGet();
    }

    @Override
    public void reportHistogramDurationSamples(
        String name,
        Map<String, String> tags,
        com.uber.m3.tally.Buckets buckets,
        com.uber.m3.util.Duration bucketLowerBound,
        com.uber.m3.util.Duration bucketUpperBound,
        long samples) {
      histogramReports.incrementAndGet();
    }

    @Override
    public Capabilities capabilities() {
      return CapableOf.REPORTING_TAGGING;
    }

    @Override
    public void flush() {
      flushes.incrementAndGet();
    }

    @Override
    public void close() {
      closes.incrementAndGet();
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

  public static final class TimeoutRecordingTracerProvider implements TracerProvider {
    private final AtomicLong joinTimeoutMillis = new AtomicLong(-1);

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
  }

  public static final class TimeoutRecordingMeterProvider implements MeterProvider {
    private final AtomicLong joinTimeoutMillis = new AtomicLong(-1);

    @Override
    public MeterBuilder meterBuilder(String instrumentationName) {
      return MeterProvider.noop().meterBuilder(instrumentationName);
    }

    public TimeoutRecordingResult forceFlush() {
      return new TimeoutRecordingResult(joinTimeoutMillis);
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
        public void awaitTermination(java.time.Duration timeout) {}

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
