package io.temporal.testing.internal.devserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.testing.TemporalDevServerOptions;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration coverage for automatic port collision recovery using the pinned real Temporal CLI.
 */
@EnabledIfSystemProperty(named = SdkJavaTestServerProfile.ACTIVE_PROPERTY, matches = "true")
class TemporalDevServerLauncherIntegrationTest {
  private static Path temporalCli;

  @TempDir Path tempDirectory;

  @BeforeAll
  static void prepareTemporalCli() {
    temporalCli = SdkJavaTestServerProfile.prepare();
  }

  @Test
  void automaticPortCollisionIsRetried() throws IOException {
    try (ServerSocket occupiedPort = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
      AtomicInteger selections = new AtomicInteger();
      IntSupplier ports =
          () -> {
            if (selections.getAndIncrement() == 0) {
              return occupiedPort.getLocalPort();
            }
            try (ServerSocket availablePort =
                new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
              return availablePort.getLocalPort();
            } catch (IOException e) {
              throw new IllegalStateException(e);
            }
          };
      TemporalDevServerOptions options =
          TemporalDevServerOptions.newBuilder()
              .setExistingPath(temporalCli.toString())
              .setStartupTimeout(Duration.ofSeconds(60))
              .setLogFile(tempDirectory.resolve("server.log").toString())
              .build();

      try (TemporalDevServerLauncher.RunningServer ignored =
          TemporalDevServerLauncher.start("default", options, ports)) {
        assertEquals(2, selections.get());
      }
    }
  }
}
