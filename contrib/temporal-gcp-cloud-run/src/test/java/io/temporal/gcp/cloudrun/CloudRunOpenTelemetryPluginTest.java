package io.temporal.gcp.cloudrun;

import static org.junit.Assert.*;

import io.opentelemetry.api.OpenTelemetry;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.opentelemetry.OpenTelemetryPlugin;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactoryOptions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CloudRunOpenTelemetryPluginTest {
  @Test
  public void defaultsToLocalCollectorAndGenericServiceName() {
    CloudRunOpenTelemetryPlugin.Builder builder =
        CloudRunOpenTelemetryPlugin.newBuilder(new HashMap<>());

    assertEquals("http://localhost:4317", builder.getEndpoint());
    assertEquals("temporal-worker", builder.getServiceName());
    assertEquals(Duration.ofSeconds(60), builder.getMetricsReportInterval());
  }

  @Test
  public void resolvesCloudRunServiceNameWithExpectedPrecedence() {
    Map<String, String> env = new HashMap<>();
    env.put(CloudRunOpenTelemetryPlugin.K_SERVICE, "cloud-run-service");
    assertEquals("cloud-run-service", CloudRunOpenTelemetryPlugin.newBuilder(env).getServiceName());

    env.put(CloudRunOpenTelemetryPlugin.CLOUD_RUN_WORKER_POOL, "worker-pool");
    assertEquals("worker-pool", CloudRunOpenTelemetryPlugin.newBuilder(env).getServiceName());

    env.put(CloudRunOpenTelemetryPlugin.OTEL_SERVICE_NAME, "otel-service");
    assertEquals("otel-service", CloudRunOpenTelemetryPlugin.newBuilder(env).getServiceName());

    assertEquals(
        "builder-service",
        CloudRunOpenTelemetryPlugin.newBuilder(env)
            .setServiceName("builder-service")
            .getServiceName());
  }

  @Test
  public void ignoresEmptyEnvironmentValues() {
    Map<String, String> env = new HashMap<>();
    env.put(CloudRunOpenTelemetryPlugin.OTEL_SERVICE_NAME, " ");
    env.put(CloudRunOpenTelemetryPlugin.CLOUD_RUN_WORKER_POOL, "");
    env.put(CloudRunOpenTelemetryPlugin.K_SERVICE, "cloud-run-service");

    assertEquals("cloud-run-service", CloudRunOpenTelemetryPlugin.newBuilder(env).getServiceName());
  }

  @Test
  public void buildAppliesResolvedEndpointAndServiceName() {
    Map<String, String> env = new HashMap<>();
    env.put(CloudRunOpenTelemetryPlugin.OTEL_EXPORTER_OTLP_ENDPOINT, "http://collector:4317");
    env.put(CloudRunOpenTelemetryPlugin.CLOUD_RUN_WORKER_POOL, "worker-pool");

    CloudRunOpenTelemetryPlugin plugin =
        CloudRunOpenTelemetryPlugin.newBuilder(env).setOpenTelemetry(OpenTelemetry.noop()).build();

    assertEquals("http://collector:4317", plugin.getEndpoint());
    assertEquals("worker-pool", plugin.getServiceName());
  }

  @Test
  public void installsMetricsScopeAndTracingInterceptors() {
    CloudRunOpenTelemetryPlugin plugin =
        CloudRunOpenTelemetryPlugin.newBuilder(new HashMap<>())
            .setOpenTelemetry(OpenTelemetry.noop())
            .build();
    WorkflowServiceStubsOptions.Builder serviceOptions = WorkflowServiceStubsOptions.newBuilder();
    WorkflowClientOptions.Builder clientOptions = WorkflowClientOptions.newBuilder();
    WorkerFactoryOptions.Builder factoryOptions = WorkerFactoryOptions.newBuilder();

    plugin.configureServiceStubs(serviceOptions);
    plugin.configureWorkflowClient(clientOptions);
    plugin.configureWorkerFactory(factoryOptions);

    assertEquals(OpenTelemetryPlugin.NAME, plugin.getName());
    assertNotNull(serviceOptions.build().getMetricsScope());
    assertEquals(1, clientOptions.build().getInterceptors().length);
    assertEquals(1, factoryOptions.build().getWorkerInterceptors().length);
  }

  @Test
  public void workerFactoryShutdownDefersFlushByDefault() {
    AtomicInteger flushes = new AtomicInteger();
    AtomicInteger shutdowns = new AtomicInteger();
    CloudRunOpenTelemetryPlugin plugin =
        CloudRunOpenTelemetryPlugin.newBuilder(new HashMap<>())
            .setOpenTelemetry(OpenTelemetry.noop())
            .setFlushHook(flushes::incrementAndGet)
            .build();

    plugin.shutdownWorkerFactory(null, factory -> shutdowns.incrementAndGet());

    assertEquals(1, shutdowns.get());
    assertEquals(0, flushes.get());

    plugin.newFlushHook().run();

    assertEquals(1, flushes.get());
  }

  @Test
  public void workerFactoryShutdownFlushCanBeEnabled() {
    AtomicInteger flushes = new AtomicInteger();
    CloudRunOpenTelemetryPlugin plugin =
        CloudRunOpenTelemetryPlugin.newBuilder(new HashMap<>())
            .setOpenTelemetry(OpenTelemetry.noop())
            .setFlushHook(flushes::incrementAndGet)
            .setFlushOnWorkerFactoryShutdown(true)
            .build();

    plugin.shutdownWorkerFactory(null, factory -> {});

    assertEquals(1, flushes.get());
  }
}
