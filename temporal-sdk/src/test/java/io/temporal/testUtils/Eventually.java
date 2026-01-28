package io.temporal.testUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

public class Eventually {
  public static void assertEventually(Duration timeout, Runnable command) {
    assertEventually(
        timeout,
        () -> {
          command.run();
          return null;
        });
  }

  public static <T> T assertEventually(Duration timeout, Supplier<T> command) {
    final Instant start = Instant.now();
    final Instant deadline = start.plus(timeout);

    while (true) {
      try {
        return command.get();
      } catch (Throwable t) {
        if (Instant.now().isAfter(deadline)) {
          throw t;
        }
        // Try again after a short nap
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
    }
  }
}
