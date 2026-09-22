package io.temporal.opentelemetry;

import static org.junit.Assert.*;

import com.uber.m3.tally.Capabilities;
import com.uber.m3.tally.CapableOf;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.DefaultAggregationSelector;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactoryOptions;
import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class OpenTelemetryWorkerTest {
  @Test
  public void defaultsResolveEndpointAndServiceName() {
    assertEquals(
        "http://localhost:4317", OpenTelemetryWorker.newBuilder(new HashMap<>()).getEndpoint());
    assertEquals(
        "temporal-worker", OpenTelemetryWorker.newBuilder(new HashMap<>()).getServiceName());

    Map<String, String> env = new HashMap<>();
    env.put(OpenTelemetryWorker.OTEL_EXPORTER_OTLP_ENDPOINT, "http://collector:4317");
    assertEquals("http://collector:4317", OpenTelemetryWorker.newBuilder(env).getEndpoint());
    assertEquals("temporal-worker", OpenTelemetryWorker.newBuilder(env).getServiceName());

    env.put(OpenTelemetryWorker.OTEL_SERVICE_NAME, "explicit-service");
    assertEquals("explicit-service", OpenTelemetryWorker.newBuilder(env).getServiceName());
  }

  @Test
  public void defaultFactoryCreatesExporterBackedSdkProviders() {
    OpenTelemetry openTelemetry =
        OpenTelemetryWorker.newBuilder(new HashMap<>()).createOpenTelemetry();

    assertTrue(openTelemetry instanceof OpenTelemetrySdk);

    OpenTelemetrySdk sdk = (OpenTelemetrySdk) openTelemetry;
    assertNotNull(sdk.getSdkTracerProvider());
    assertNotNull(sdk.getSdkMeterProvider());
    sdk.shutdown().join(1, TimeUnit.SECONDS);
  }

  @Test
  public void defaultFactoryConfiguresTraceContextPropagator() {
    OpenTelemetry openTelemetry =
        OpenTelemetryWorker.newBuilder(new HashMap<>()).createOpenTelemetry();

    assertTrue(openTelemetry instanceof OpenTelemetrySdk);

    OpenTelemetrySdk sdk = (OpenTelemetrySdk) openTelemetry;
    String traceId = "00000000000000000000000000000001";
    String spanId = "0000000000000002";
    Map<String, String> carrier = new HashMap<>();
    sdk.getPropagators()
        .getTextMapPropagator()
        .inject(
            io.opentelemetry.context.Context.root()
                .with(
                    Span.wrap(
                        SpanContext.create(
                            traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()))),
            carrier,
            (map, key, value) -> map.put(key, value));

    assertEquals("00-" + traceId + "-" + spanId + "-01", carrier.get("traceparent"));
    sdk.shutdown().join(1, TimeUnit.SECONDS);
  }

  @Test
  public void exporterFactoryReceivesResolvedEndpointAndServiceName() {
    Map<String, String> env = new HashMap<>();
    env.put(OpenTelemetryWorker.OTEL_EXPORTER_OTLP_ENDPOINT, "http://collector:4317");
    env.put(OpenTelemetryWorker.OTEL_SERVICE_NAME, "service-name");
    RecordingTelemetryFactory factory = new RecordingTelemetryFactory();
    Config config = new Config();

    OpenTelemetryWorker.newBuilder(env)
        .setTelemetryFactory(factory)
        .apply(
            config.serviceStubsOptions,
            config.clientOptions,
            config.workerFactoryOptions,
            config.shutdownHooks::add);

    assertEquals(1, factory.creates.get());
    assertEquals("http://collector:4317", factory.endpoint);
    assertEquals("service-name", factory.serviceName);
  }

  @Test
  public void customEndpointAndServiceNameAreUsedByExporterFactory() {
    RecordingTelemetryFactory factory = new RecordingTelemetryFactory();
    Config config = new Config();

    OpenTelemetryWorker.newBuilder(new HashMap<>())
        .setTelemetryFactory(factory)
        .setEndpoint("http://custom-collector:4317")
        .setServiceName("custom-service")
        .setMetricsReportInterval(Duration.ofSeconds(3))
        .setFlushTimeout(Duration.ofSeconds(4))
        .apply(
            config.serviceStubsOptions,
            config.clientOptions,
            config.workerFactoryOptions,
            config.shutdownHooks::add);

    assertEquals(1, factory.creates.get());
    assertEquals("http://custom-collector:4317", factory.endpoint);
    assertEquals("custom-service", factory.serviceName);
    assertEquals(Duration.ofSeconds(3), factory.metricsReportInterval);
    assertEquals(Duration.ofSeconds(4), factory.flushTimeout);
  }

  @Test
  public void metricsScopeAndTracingInterceptorsAreInstalled() {
    Config config = new Config();

    OpenTelemetryWorker.configure(
        config.serviceStubsOptions,
        config.clientOptions,
        config.workerFactoryOptions,
        config.shutdownHooks::add,
        builder -> builder.setOpenTelemetry(OpenTelemetry.noop()).setFlushHook(() -> {}));

    assertNotNull(config.serviceStubsOptions.build().getMetricsScope());
    assertEquals(1, config.clientOptions.build().getInterceptors().length);
    assertEquals(1, config.workerFactoryOptions.build().getWorkerInterceptors().length);
  }

  @Test
  public void configureRegistersTallyFlushBeforeOpenTelemetryFlush() {
    Config config = new Config();

    OpenTelemetryWorker.configure(
        config.serviceStubsOptions,
        config.clientOptions,
        config.workerFactoryOptions,
        config.shutdownHooks::add,
        builder -> builder.setOpenTelemetry(OpenTelemetry.noop()));

    assertEquals(2, config.shutdownHooks.size());
    assertTrue(config.shutdownHooks.get(0) instanceof TallyScopeFlushHook);
    assertTrue(config.shutdownHooks.get(1) instanceof OpenTelemetryFlushHook);
  }

  @Test
  public void metricsOnlyInstallsScopeWithoutTracingInterceptors() {
    Config config = new Config();

    OpenTelemetryWorker.configureMetrics(
        config.serviceStubsOptions, config.shutdownHooks::add, OpenTelemetry.noop());

    assertNotNull(config.serviceStubsOptions.build().getMetricsScope());
    assertEquals(0, clientInterceptorCount(config.clientOptions));
    assertEquals(0, workerInterceptorCount(config.workerFactoryOptions));
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
  public void tracingOnlyInstallsInterceptorsWithoutMetricsScope() {
    Config config = new Config();

    OpenTelemetryWorker.configureTracing(
        config.clientOptions, config.workerFactoryOptions, OpenTelemetry.noop());

    assertNull(config.serviceStubsOptions.build().getMetricsScope());
    assertEquals(1, clientInterceptorCount(config.clientOptions));
    assertEquals(1, workerInterceptorCount(config.workerFactoryOptions));
  }

  @Test
  public void flushHookUsesSmallerConfiguredAndCallerTimeout() {
    FakeMonotonicClock clock = new FakeMonotonicClock();
    TimeoutRecordingOpenTelemetry openTelemetry = new TimeoutRecordingOpenTelemetry();

    new OpenTelemetryFlushHook(openTelemetry, Duration.ofMillis(250), clock)
        .run(Duration.ofSeconds(2));

    assertEquals(250, openTelemetry.tracerProvider.joinTimeoutMillis.get());
    assertEquals(250, openTelemetry.meterProvider.joinTimeoutMillis.get());

    clock = new FakeMonotonicClock();
    openTelemetry = new TimeoutRecordingOpenTelemetry();

    new OpenTelemetryFlushHook(openTelemetry, Duration.ofSeconds(2), clock)
        .run(Duration.ofMillis(125));

    assertEquals(125, openTelemetry.tracerProvider.joinTimeoutMillis.get());
    assertEquals(125, openTelemetry.meterProvider.joinTimeoutMillis.get());
  }

  @Test
  public void flushHookSpendsTimeoutOnceAcrossProviders() {
    FakeMonotonicClock clock = new FakeMonotonicClock();
    TimeoutRecordingOpenTelemetry openTelemetry =
        new TimeoutRecordingOpenTelemetry(clock, Duration.ofMillis(150));

    new OpenTelemetryFlushHook(openTelemetry, Duration.ofMillis(250), clock)
        .run(Duration.ofSeconds(2));

    assertEquals(250, openTelemetry.tracerProvider.joinTimeoutMillis.get());
    assertEquals(100, openTelemetry.meterProvider.joinTimeoutMillis.get());
  }

  @Test
  public void flushHookUnwrapsSdkProviders() {
    RecordingSpanProcessor spanProcessor = new RecordingSpanProcessor();
    RecordingMetricReader metricReader = new RecordingMetricReader();
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build())
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .build();
    try {
      new OpenTelemetryFlushHook(sdk, Duration.ofSeconds(1), new FakeMonotonicClock())
          .run(Duration.ofSeconds(1));

      assertEquals(1, spanProcessor.flushes.get());
      assertEquals(1, metricReader.flushes.get());
    } finally {
      sdk.shutdown().join(1, TimeUnit.SECONDS);
    }
  }

  @Test
  public void customOpenTelemetryBypassesExporterCreation() {
    RecordingTelemetryFactory factory = new RecordingTelemetryFactory();
    Config config = new Config();

    OpenTelemetryWorker.newBuilder(new HashMap<>())
        .setTelemetryFactory(factory)
        .setOpenTelemetry(OpenTelemetry.noop())
        .apply(
            config.serviceStubsOptions,
            config.clientOptions,
            config.workerFactoryOptions,
            config.shutdownHooks::add);

    assertEquals(0, factory.creates.get());
    assertNotNull(config.serviceStubsOptions.build().getMetricsScope());
  }

  private int clientInterceptorCount(WorkflowClientOptions.Builder options) {
    io.temporal.common.interceptors.WorkflowClientInterceptor[] interceptors =
        options.build().getInterceptors();
    return interceptors == null ? 0 : interceptors.length;
  }

  private int workerInterceptorCount(WorkerFactoryOptions.Builder options) {
    io.temporal.common.interceptors.WorkerInterceptor[] interceptors =
        options.build().getWorkerInterceptors();
    return interceptors == null ? 0 : interceptors.length;
  }

  private static final class Config {
    private final WorkflowServiceStubsOptions.Builder serviceStubsOptions =
        WorkflowServiceStubsOptions.newBuilder();
    private final WorkflowClientOptions.Builder clientOptions = WorkflowClientOptions.newBuilder();
    private final WorkerFactoryOptions.Builder workerFactoryOptions =
        WorkerFactoryOptions.newBuilder();
    private final List<Runnable> shutdownHooks = new ArrayList<>();
  }

  private static final class RecordingTelemetryFactory
      implements OpenTelemetryWorker.TelemetryFactory {
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
        Duration flushTimeout,
        IdGenerator idGenerator) {
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

  private static final class RecordingSpanProcessor implements SpanProcessor {
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger shutdowns = new AtomicInteger();

    @Override
    public void onStart(io.opentelemetry.context.Context parentContext, ReadWriteSpan span) {}

    @Override
    public boolean isStartRequired() {
      return false;
    }

    @Override
    public void onEnd(ReadableSpan span) {}

    @Override
    public boolean isEndRequired() {
      return false;
    }

    @Override
    public CompletableResultCode forceFlush() {
      flushes.incrementAndGet();
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      shutdowns.incrementAndGet();
      return CompletableResultCode.ofSuccess();
    }
  }

  private static final class RecordingMetricReader implements MetricReader {
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger shutdowns = new AtomicInteger();

    @Override
    public void register(CollectionRegistration registration) {}

    @Override
    public CompletableResultCode forceFlush() {
      flushes.incrementAndGet();
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      shutdowns.incrementAndGet();
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
      return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
      return DefaultAggregationSelector.getDefault().getDefaultAggregation(instrumentType);
    }
  }

  private static final class TimeoutRecordingOpenTelemetry implements OpenTelemetry {
    private final TimeoutRecordingTracerProvider tracerProvider;
    private final TimeoutRecordingMeterProvider meterProvider;

    private TimeoutRecordingOpenTelemetry() {
      this(null, Duration.ZERO);
    }

    private TimeoutRecordingOpenTelemetry(FakeMonotonicClock clock, Duration joinDuration) {
      this.tracerProvider = new TimeoutRecordingTracerProvider(clock, joinDuration);
      this.meterProvider = new TimeoutRecordingMeterProvider(clock, joinDuration);
    }

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
    private final FakeMonotonicClock clock;
    private final Duration joinDuration;

    private TimeoutRecordingTracerProvider(FakeMonotonicClock clock, Duration joinDuration) {
      this.clock = clock;
      this.joinDuration = joinDuration;
    }

    @Override
    public Tracer get(String instrumentationName) {
      return TracerProvider.noop().get(instrumentationName);
    }

    @Override
    public Tracer get(String instrumentationName, String instrumentationVersion) {
      return TracerProvider.noop().get(instrumentationName, instrumentationVersion);
    }

    public TimeoutRecordingResult forceFlush() {
      return new TimeoutRecordingResult(joinTimeoutMillis, clock, joinDuration);
    }
  }

  public static final class TimeoutRecordingMeterProvider implements MeterProvider {
    private final AtomicLong joinTimeoutMillis = new AtomicLong(-1);
    private final FakeMonotonicClock clock;
    private final Duration joinDuration;

    private TimeoutRecordingMeterProvider(FakeMonotonicClock clock, Duration joinDuration) {
      this.clock = clock;
      this.joinDuration = joinDuration;
    }

    @Override
    public MeterBuilder meterBuilder(String instrumentationName) {
      return MeterProvider.noop().meterBuilder(instrumentationName);
    }

    public TimeoutRecordingResult forceFlush() {
      return new TimeoutRecordingResult(joinTimeoutMillis, clock, joinDuration);
    }
  }

  public static final class TimeoutRecordingResult {
    private final AtomicLong joinTimeoutMillis;
    private final FakeMonotonicClock clock;
    private final Duration joinDuration;

    private TimeoutRecordingResult(
        AtomicLong joinTimeoutMillis, FakeMonotonicClock clock, Duration joinDuration) {
      this.joinTimeoutMillis = joinTimeoutMillis;
      this.clock = clock;
      this.joinDuration = joinDuration;
    }

    public TimeoutRecordingResult join(long timeout, TimeUnit unit) {
      joinTimeoutMillis.set(unit.toMillis(timeout));
      if (clock != null) {
        clock.advance(joinDuration);
      }
      return this;
    }
  }

  private static final class FakeMonotonicClock implements OpenTelemetryFlushHook.MonotonicClock {
    private long nowNanos;

    @Override
    public long nanoTime() {
      return nowNanos;
    }

    private void advance(Duration duration) {
      nowNanos += duration.toNanos();
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
}
