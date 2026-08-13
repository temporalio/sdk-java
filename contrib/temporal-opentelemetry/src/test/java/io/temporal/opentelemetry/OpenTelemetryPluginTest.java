package io.temporal.opentelemetry;

import static org.junit.Assert.*;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactoryOptions;
import java.io.Closeable;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class OpenTelemetryPluginTest {
  @Test
  public void defaultsResolveEndpointAndServiceName() {
    assertEquals(
        "http://localhost:4317", OpenTelemetryPlugin.newBuilder(new HashMap<>()).getEndpoint());
    assertEquals(
        "temporal-worker", OpenTelemetryPlugin.newBuilder(new HashMap<>()).getServiceName());

    Map<String, String> env = new HashMap<>();
    env.put(OpenTelemetryWorker.OTEL_EXPORTER_OTLP_ENDPOINT, "http://collector:4317");
    env.put(OpenTelemetryWorker.OTEL_SERVICE_NAME, "service-name");

    assertEquals("http://collector:4317", OpenTelemetryPlugin.newBuilder(env).getEndpoint());
    assertEquals("service-name", OpenTelemetryPlugin.newBuilder(env).getServiceName());
  }

  @Test
  public void installsMetricsScopeAndTracingInterceptors() {
    RecordingTelemetryFactory factory = new RecordingTelemetryFactory();
    OpenTelemetryPlugin plugin =
        OpenTelemetryPlugin.newBuilder(new HashMap<>()).setTelemetryFactory(factory).build();
    Config config = new Config();

    plugin.configureServiceStubs(config.serviceStubsOptions);
    plugin.configureWorkflowClient(config.clientOptions);
    plugin.configureWorkerFactory(config.workerFactoryOptions);

    assertEquals(1, factory.creates.get());
    assertNotNull(config.serviceStubsOptions.build().getMetricsScope());
    assertEquals(1, config.clientOptions.build().getInterceptors().length);
    assertEquals(1, config.workerFactoryOptions.build().getWorkerInterceptors().length);
  }

  @Test
  public void customOpenTelemetryBypassesExporterCreation() {
    RecordingTelemetryFactory factory = new RecordingTelemetryFactory();
    OpenTelemetryPlugin plugin =
        OpenTelemetryPlugin.newBuilder(new HashMap<>())
            .setTelemetryFactory(factory)
            .setOpenTelemetry(OpenTelemetry.noop())
            .build();
    Config config = new Config();

    plugin.configureServiceStubs(config.serviceStubsOptions);
    plugin.configureWorkflowClient(config.clientOptions);
    plugin.configureWorkerFactory(config.workerFactoryOptions);

    assertEquals(0, factory.creates.get());
    assertNotNull(config.serviceStubsOptions.build().getMetricsScope());
    assertEquals(1, config.clientOptions.build().getInterceptors().length);
    assertEquals(1, config.workerFactoryOptions.build().getWorkerInterceptors().length);
  }

  @Test
  public void flushHookForceFlushesProvidersWithoutClosingThem() {
    CountingOpenTelemetry openTelemetry = new CountingOpenTelemetry();
    OpenTelemetryPlugin plugin =
        OpenTelemetryPlugin.newBuilder(new HashMap<>()).setOpenTelemetry(openTelemetry).build();
    Config config = new Config();
    plugin.configureServiceStubs(config.serviceStubsOptions);

    plugin.newFlushHook().run(Duration.ofMillis(100));

    assertEquals(1, openTelemetry.tracerProvider.flushes.get());
    assertEquals(1, openTelemetry.meterProvider.flushes.get());
    assertEquals(0, openTelemetry.tracerProvider.closes.get());
    assertEquals(0, openTelemetry.meterProvider.closes.get());
  }

  @Test
  public void workerFactoryShutdownFlushCanBeDisabledForServerlessRuntimes() {
    CountingOpenTelemetry openTelemetry = new CountingOpenTelemetry();
    OpenTelemetryPlugin plugin =
        OpenTelemetryPlugin.newBuilder(new HashMap<>())
            .setOpenTelemetry(openTelemetry)
            .setFlushOnWorkerFactoryShutdown(false)
            .build();
    AtomicInteger shutdowns = new AtomicInteger();

    plugin.shutdownWorkerFactory(null, factory -> shutdowns.incrementAndGet());

    assertEquals(1, shutdowns.get());
    assertEquals(0, openTelemetry.tracerProvider.flushes.get());
    assertEquals(0, openTelemetry.meterProvider.flushes.get());
  }

  @Test
  public void workerFactoryShutdownFlushesByDefault() {
    CountingOpenTelemetry openTelemetry = new CountingOpenTelemetry();
    OpenTelemetryPlugin plugin =
        OpenTelemetryPlugin.newBuilder(new HashMap<>()).setOpenTelemetry(openTelemetry).build();
    AtomicInteger shutdowns = new AtomicInteger();

    plugin.shutdownWorkerFactory(null, factory -> shutdowns.incrementAndGet());

    assertEquals(1, shutdowns.get());
    assertEquals(1, openTelemetry.tracerProvider.flushes.get());
    assertEquals(1, openTelemetry.meterProvider.flushes.get());
  }

  private static final class Config {
    private final WorkflowServiceStubsOptions.Builder serviceStubsOptions =
        WorkflowServiceStubsOptions.newBuilder();
    private final WorkflowClientOptions.Builder clientOptions = WorkflowClientOptions.newBuilder();
    private final WorkerFactoryOptions.Builder workerFactoryOptions =
        WorkerFactoryOptions.newBuilder();
  }

  private static final class RecordingTelemetryFactory
      implements OpenTelemetryWorker.TelemetryFactory {
    private final AtomicInteger creates = new AtomicInteger();

    @Override
    public OpenTelemetry create(
        String endpoint,
        String serviceName,
        Duration metricsReportInterval,
        Duration flushTimeout,
        IdGenerator idGenerator) {
      creates.incrementAndGet();
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
