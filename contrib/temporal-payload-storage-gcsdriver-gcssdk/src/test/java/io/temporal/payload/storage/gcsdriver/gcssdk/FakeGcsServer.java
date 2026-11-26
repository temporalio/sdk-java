package io.temporal.payload.storage.gcsdriver.gcssdk;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.rules.ExternalResource;

final class FakeGcsServer extends ExternalResource {
  private Process process;
  private String endpoint;

  String endpoint() {
    return endpoint;
  }

  @Override
  protected void before() throws Throwable {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    endpoint = "http://127.0.0.1:" + port;
    Path log = Files.createTempFile("fake-gcs-server-", ".log");
    log.toFile().deleteOnExit();
    process =
        new ProcessBuilder(
                Objects.requireNonNull(
                    System.getProperty("io.temporal.testing.fakeGcsServer"),
                    "Run tests through Gradle to prepare fake-gcs-server"),
                "-scheme",
                "http",
                "-host",
                "127.0.0.1",
                "-port",
                Integer.toString(port),
                "-backend",
                "memory",
                "-external-url",
                endpoint)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (process.isAlive() && System.nanoTime() < deadline) {
        if (isReady()) {
          return;
        }
        Thread.sleep(50);
      }
      throw new IOException(
          "Fake GCS server failed to start:\n"
              + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
    } catch (Throwable error) {
      after();
      throw error;
    }
  }

  private boolean isReady() throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection)
            new URL(endpoint + "/storage/v1/b?project=temporal-test").openConnection();
    connection.setConnectTimeout(1000);
    connection.setReadTimeout(1000);
    try {
      return connection.getResponseCode() == 200;
    } catch (IOException e) {
      return false;
    } finally {
      connection.disconnect();
    }
  }

  @Override
  protected void after() {
    if (process == null) {
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          throw new AssertionError("Fake GCS server did not stop");
        }
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }
}
