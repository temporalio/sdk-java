package io.temporal.gcp.cloudrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link WorkerIdPlugin}.
 *
 * <p>The metadata request is served by an in-process {@link HttpServer} and the environment lookup
 * is injected through the {@link GoogleCloudRunMetadata#fetch(String, Duration,
 * java.util.function.Function)} test seam, so these tests touch neither the network nor the real
 * process environment. The plugin's package-private {@link WorkerIdPlugin#WorkerIdPlugin(Supplier)}
 * seam lets each test point the plugin at that in-process server (or at an unreachable address, to
 * exercise the off-platform fail-fast path).
 */
public class WorkerIdPluginTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private HttpServer server;
  private final AtomicReference<String> responseBody = new AtomicReference<>("");

  @Before
  public void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/computeMetadata/v1/instance/id",
        exchange -> {
          byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  @After
  public void stopServer() {
    server.stop(0);
  }

  @Test
  public void configureWorkflowClientSetsDerivedIdentityWhenUnset() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "worker-pool");
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_REVISION, "revision-1");

    WorkflowClientOptions.Builder builder = WorkflowClientOptions.newBuilder();
    pluginFor(env).configureWorkflowClient(builder);

    assertEquals("instance-1@revision-1", builder.build().getIdentity());
  }

  @Test
  public void configureWorkflowClientPreservesUserProvidedIdentity() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "worker-pool");
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_REVISION, "revision-1");

    WorkflowClientOptions.Builder builder =
        WorkflowClientOptions.newBuilder().setIdentity("user-set");
    pluginFor(env).configureWorkflowClient(builder);

    assertEquals("user-set", builder.build().getIdentity());
  }

  @Test
  public void configureWorkerEnablesPinnedVersioning() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "worker-pool");
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_REVISION, "revision-1");

    WorkerOptions.Builder builder = WorkerOptions.newBuilder();
    pluginFor(env).configureWorker("orders", builder);

    WorkerDeploymentOptions deploymentOptions = builder.build().getDeploymentOptions();
    assertTrue(deploymentOptions.isUsingVersioning());
    assertEquals(
        new WorkerDeploymentVersion("worker-pool", "revision-1"), deploymentOptions.getVersion());
    assertEquals(VersioningBehavior.PINNED, deploymentOptions.getDefaultVersioningBehavior());
  }

  @Test
  public void configureWorkflowClientFailsFastOffCloudRun() {
    String unreachableUrl =
        "http://127.0.0.1:" + reserveUnusedPort() + "/computeMetadata/v1/instance/id";
    WorkerIdPlugin plugin =
        new WorkerIdPlugin(
            () -> GoogleCloudRunMetadata.fetch(unreachableUrl, TIMEOUT, name -> null));

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> plugin.configureWorkflowClient(WorkflowClientOptions.newBuilder()));
    assertTrue(e.getMessage().contains("metadata server"));
  }

  @Test
  public void configureWorkerFailsFastWhenNotWorkerPoolOrService() {
    responseBody.set("instance-1");

    // Metadata server is reachable (instance id is present) but no name/revision env is set, so the
    // deployment version cannot be built. This is the "on some other platform" case.
    WorkerIdPlugin plugin = new WorkerIdPlugin(metadata(new HashMap<>()));

    assertThrows(
        IllegalStateException.class,
        () -> plugin.configureWorker("orders", WorkerOptions.newBuilder()));
  }

  @Test
  public void metadataIsFetchedOnceAndSharedByBothHooks() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "worker-pool");
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_REVISION, "revision-1");

    GoogleCloudRunMetadata resolved = metadata(env);
    AtomicInteger supplierCalls = new AtomicInteger();
    Supplier<GoogleCloudRunMetadata> countingSupplier =
        () -> {
          supplierCalls.incrementAndGet();
          return resolved;
        };
    WorkerIdPlugin plugin = new WorkerIdPlugin(countingSupplier);

    plugin.configureWorkflowClient(WorkflowClientOptions.newBuilder());
    plugin.configureWorker("orders", WorkerOptions.newBuilder());

    assertEquals(1, supplierCalls.get());
  }

  @Test
  public void injectedMetadataIsUsedWithoutFetching() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "worker-pool");
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_REVISION, "revision-1");

    WorkerIdPlugin plugin = new WorkerIdPlugin(metadata(env));

    WorkflowClientOptions.Builder builder = WorkflowClientOptions.newBuilder();
    plugin.configureWorkflowClient(builder);

    assertEquals("instance-1@revision-1", builder.build().getIdentity());
  }

  private WorkerIdPlugin pluginFor(Map<String, String> env) {
    return new WorkerIdPlugin(() -> metadata(env));
  }

  private GoogleCloudRunMetadata metadata(Map<String, String> env) {
    return GoogleCloudRunMetadata.fetch(metadataUrl(), TIMEOUT, env::get);
  }

  private String metadataUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/computeMetadata/v1/instance/id";
  }

  private static int reserveUnusedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
