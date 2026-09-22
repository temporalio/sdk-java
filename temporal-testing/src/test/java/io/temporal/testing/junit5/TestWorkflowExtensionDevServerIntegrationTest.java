package io.temporal.testing.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.testing.TemporalDevServerOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.testing.internal.devserver.SdkJavaTestServerProfile;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Integration coverage for extension ownership using sdk-java's pinned real Temporal CLI. */
@EnabledIfSystemProperty(named = SdkJavaTestServerProfile.ACTIVE_PROPERTY, matches = "true")
class TestWorkflowExtensionDevServerIntegrationTest {
  private static String target;

  @RegisterExtension
  static final TestWorkflowExtension EXTENSION =
      TestWorkflowExtension.newBuilder().useDevServer(realServerOptions()).build();

  @Test
  void extensionUsesOwnedDevServer(TestWorkflowEnvironment environment) {
    assertEquals("UnitTest", environment.getNamespace());
    assertTrue(environment.isStarted());
    target = environment.getWorkflowServiceStubs().getOptions().getTarget();
    assertTrue(canConnect(target));
  }

  @AfterAll
  static void serverWasClosedByExtension() throws InterruptedException {
    assertTrue(awaitClosed(target));
  }

  private static TemporalDevServerOptions realServerOptions() {
    if (!SdkJavaTestServerProfile.isActive()) {
      return TemporalDevServerOptions.newBuilder().setExistingPath("profile-disabled").build();
    }
    return TemporalDevServerOptions.newBuilder()
        .setExistingPath(SdkJavaTestServerProfile.prepare().toString())
        .setStartupTimeout(Duration.ofSeconds(60))
        .build();
  }

  private static boolean awaitClosed(String target) throws InterruptedException {
    for (int i = 0; i < 50; i++) {
      if (!canConnect(target)) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }
    return false;
  }

  private static boolean canConnect(String target) {
    int separator = target.lastIndexOf(':');
    String host = target.substring(0, separator);
    int port = Integer.parseInt(target.substring(separator + 1));
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 250);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
