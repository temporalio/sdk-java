package io.temporal.gcp;

import static org.junit.Assert.*;

import io.opentelemetry.api.OpenTelemetry;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.opentelemetry.OpenTelemetryPlugin;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GcpOpenTelemetryPluginTest {
  @Test
  public void defaultsToLocalCollectorAndGenericServiceName() {
    GcpOpenTelemetryPlugin.Builder builder = GcpOpenTelemetryPlugin.newBuilder(new HashMap<>());

    assertEquals("http://localhost:4317", builder.getEndpoint());
    assertEquals("temporal-worker", builder.getServiceName());
  }

  @Test
  public void resolvesCloudRunServiceNameWithExpectedPrecedence() {
    Map<String, String> env = new HashMap<>();
    env.put(GcpOpenTelemetryPlugin.K_SERVICE, "cloud-run-service");
    assertEquals("cloud-run-service", GcpOpenTelemetryPlugin.newBuilder(env).getServiceName());

    env.put(GcpOpenTelemetryPlugin.CLOUD_RUN_WORKER_POOL, "worker-pool");
    assertEquals("worker-pool", GcpOpenTelemetryPlugin.newBuilder(env).getServiceName());

    env.put(GcpOpenTelemetryPlugin.OTEL_SERVICE_NAME, "otel-service");
    assertEquals("otel-service", GcpOpenTelemetryPlugin.newBuilder(env).getServiceName());

    assertEquals(
        "builder-service",
        GcpOpenTelemetryPlugin.newBuilder(env).setServiceName("builder-service").getServiceName());
  }

  @Test
  public void ignoresEmptyEnvironmentValues() {
    Map<String, String> env = new HashMap<>();
    env.put(GcpOpenTelemetryPlugin.OTEL_SERVICE_NAME, " ");
    env.put(GcpOpenTelemetryPlugin.CLOUD_RUN_WORKER_POOL, "");
    env.put(GcpOpenTelemetryPlugin.K_SERVICE, "cloud-run-service");

    assertEquals("cloud-run-service", GcpOpenTelemetryPlugin.newBuilder(env).getServiceName());
  }

  @Test
  public void buildAppliesResolvedEndpointAndServiceName() {
    Map<String, String> env = new HashMap<>();
    env.put(GcpOpenTelemetryPlugin.OTEL_EXPORTER_OTLP_ENDPOINT, "http://collector:4317");
    env.put(GcpOpenTelemetryPlugin.CLOUD_RUN_WORKER_POOL, "worker-pool");

    GcpOpenTelemetryPlugin plugin =
        GcpOpenTelemetryPlugin.newBuilder(env).setOpenTelemetry(OpenTelemetry.noop()).build();

    assertEquals("http://collector:4317", plugin.getEndpoint());
    assertEquals("worker-pool", plugin.getServiceName());
  }

  @Test
  public void installsMetricsScopeAndTracingInterceptors() {
    GcpOpenTelemetryPlugin plugin =
        GcpOpenTelemetryPlugin.newBuilder(new HashMap<>())
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
  public void workerFactoryShutdownFlushesByDefault() {
    AtomicInteger flushes = new AtomicInteger();
    AtomicInteger shutdowns = new AtomicInteger();
    GcpOpenTelemetryPlugin plugin =
        GcpOpenTelemetryPlugin.newBuilder(new HashMap<>())
            .setOpenTelemetry(OpenTelemetry.noop())
            .setFlushHook(flushes::incrementAndGet)
            .build();

    plugin.shutdownWorkerFactory(null, factory -> shutdowns.incrementAndGet());

    assertEquals(1, shutdowns.get());
    assertEquals(1, flushes.get());
  }
}
