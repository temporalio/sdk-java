package io.temporal.opentelemetry;

import java.time.Duration;

/** Shutdown hook that can use the caller's remaining cleanup timeout. */
public interface TimedShutdownHook extends Runnable {
  void run(Duration timeout);
}
