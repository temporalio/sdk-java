package io.temporal.opentelemetry;

import java.time.Duration;
import javax.annotation.Nonnull;

/** Shutdown hook that can use the caller's remaining cleanup timeout. */
public interface TimedShutdownHook extends Runnable {
  void run(@Nonnull Duration timeout);
}
