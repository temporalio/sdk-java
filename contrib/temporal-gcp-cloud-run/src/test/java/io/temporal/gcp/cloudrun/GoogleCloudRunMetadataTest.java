package io.temporal.gcp.cloudrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.temporal.common.WorkerDeploymentVersion;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link GoogleCloudRunMetadata}.
 *
 * <p>The metadata request is served by an in-process {@link HttpServer} and the environment lookup
 * is injected through the package-private {@link GoogleCloudRunMetadata#fetch(String, Duration,
 * java.util.function.Function)} test seam, so these tests touch neither the network nor the real
 * process environment.
 */
public class GoogleCloudRunMetadataTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private HttpServer server;
  private final AtomicReference<String> responseBody = new AtomicReference<>("");
  private final AtomicInteger responseStatus = new AtomicInteger(200);
  private final AtomicReference<String> capturedMetadataFlavor = new AtomicReference<>();
  private final AtomicReference<String> capturedMethod = new AtomicReference<>();

  @Before
  public void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/computeMetadata/v1/instance/id",
        exchange -> {
          capturedMetadataFlavor.set(exchange.getRequestHeaders().getFirst("Metadata-Flavor"));
          capturedMethod.set(exchange.getRequestMethod());
          byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(responseStatus.get(), body.length == 0 ? -1 : body.length);
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

  // --- Environment-variable precedence ---

  @Test
  public void cloudRunWorkerPoolWinsOverKService() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "worker-pool");
    env.put(GoogleCloudRunMetadata.K_SERVICE, "service");
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_REVISION, "worker-pool-revision");
    env.put(GoogleCloudRunMetadata.K_REVISION, "service-revision");

    GoogleCloudRunMetadata metadata = fetch(env);

    assertEquals("worker-pool", metadata.getName());
    assertEquals("worker-pool-revision", metadata.getRevision());
  }

  @Test
  public void kServiceUsedWhenWorkerPoolAbsent() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.K_SERVICE, "service");
    env.put(GoogleCloudRunMetadata.K_REVISION, "service-revision");

    GoogleCloudRunMetadata metadata = fetch(env);

    assertEquals("service", metadata.getName());
    assertEquals("service-revision", metadata.getRevision());
  }

  @Test
  public void blankWorkerPoolVariablesFallThroughToKService() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "   ");
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_REVISION, "");
    env.put(GoogleCloudRunMetadata.K_SERVICE, "service");
    env.put(GoogleCloudRunMetadata.K_REVISION, "service-revision");

    GoogleCloudRunMetadata metadata = fetch(env);

    assertEquals("service", metadata.getName());
    assertEquals("service-revision", metadata.getRevision());
  }

  @Test
  public void nameAndRevisionAreNullWhenNoEnvSet() {
    responseBody.set("instance-1");

    GoogleCloudRunMetadata metadata = fetch(new HashMap<>());

    assertNull(metadata.getName());
    assertNull(metadata.getRevision());
  }

  // --- Worker identity ---

  @Test
  public void workerIdentityCombinesInstanceIdAndRevision() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "worker-pool");
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_REVISION, "revision-1");

    assertEquals("instance-1@revision-1", fetch(env).workerIdentity());
  }

  @Test
  public void workerIdentityFallsBackToNameWhenRevisionBlank() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "worker-pool");

    assertEquals("instance-1@worker-pool", fetch(env).workerIdentity());
  }

  @Test
  public void workerIdentityFallsBackToInstanceIdWhenNameAndRevisionBlank() {
    responseBody.set("instance-1");

    assertEquals("instance-1", fetch(new HashMap<>()).workerIdentity());
  }

  // --- Worker deployment version ---

  @Test
  public void workerDeploymentVersionMapsNameToDeploymentAndRevisionToBuildId() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "worker-pool");
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_REVISION, "revision-1");

    WorkerDeploymentVersion version = fetch(env).workerDeploymentVersion();

    assertEquals("worker-pool", version.getDeploymentName());
    assertEquals("revision-1", version.getBuildId());
  }

  @Test
  public void workerDeploymentVersionRequiresName() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_REVISION, "revision-1");

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> fetch(env).workerDeploymentVersion());
    assertTrue(e.getMessage().contains("name and revision"));
  }

  @Test
  public void workerDeploymentVersionRequiresRevision() {
    responseBody.set("instance-1");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "worker-pool");

    assertThrows(IllegalStateException.class, () -> fetch(env).workerDeploymentVersion());
  }

  // --- Metadata HTTP request ---

  @Test
  public void fetchSendsMetadataFlavorHeaderAndTrimsBody() {
    responseBody.set("  instance-42\n");
    Map<String, String> env = new HashMap<>();
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_WORKER_POOL, "worker-pool");
    env.put(GoogleCloudRunMetadata.CLOUD_RUN_REVISION, "revision-1");

    GoogleCloudRunMetadata metadata = fetch(env);

    assertEquals("instance-42", metadata.getInstanceId());
    assertEquals("Google", capturedMetadataFlavor.get());
    assertEquals("GET", capturedMethod.get());
  }

  @Test
  public void fetchThrowsOnNonSuccessStatus() {
    responseStatus.set(500);
    responseBody.set("boom");

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> fetch(new HashMap<>()));
    assertTrue(e.getMessage().contains("metadata server"));
  }

  @Test
  public void fetchThrowsWhenServerUnreachable() {
    String unreachableUrl =
        "http://127.0.0.1:" + reserveUnusedPort() + "/computeMetadata/v1/instance/id";
    Map<String, String> env = new HashMap<>();

    assertThrows(
        IllegalStateException.class,
        () -> GoogleCloudRunMetadata.fetch(unreachableUrl, TIMEOUT, env::get));
  }

  private GoogleCloudRunMetadata fetch(Map<String, String> env) {
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
