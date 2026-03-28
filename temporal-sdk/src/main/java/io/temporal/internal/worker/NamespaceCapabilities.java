package io.temporal.internal.worker;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds namespace-level capabilities discovered from the server's DescribeNamespace response. A
 * single instance is shared across all workers in a WorkerFactory and is populated at startup. Uses
 * AtomicBooleans so capabilities can be set after construction.
 */
public final class NamespaceCapabilities {
  private final AtomicBoolean pollerAutoscaling = new AtomicBoolean(false);
  private final AtomicBoolean gracefulPollShutdown = new AtomicBoolean(false);

  public boolean isPollerAutoscaling() {
    return pollerAutoscaling.get();
  }

  public void setPollerAutoscaling(boolean value) {
    pollerAutoscaling.set(value);
  }

  public boolean isGracefulPollShutdown() {
    return gracefulPollShutdown.get();
  }

  public void setGracefulPollShutdown(boolean value) {
    gracefulPollShutdown.set(value);
  }
}
