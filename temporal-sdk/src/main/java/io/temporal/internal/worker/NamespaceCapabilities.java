package io.temporal.internal.worker;

import io.temporal.api.namespace.v1.NamespaceInfo.Capabilities;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds namespace-level capabilities discovered from the server's DescribeNamespace response. A
 * single instance is shared across all workers in a WorkerFactory and is populated at startup. Uses
 * AtomicBooleans so capabilities can be set after construction.
 */
public final class NamespaceCapabilities {
  private final AtomicBoolean pollerAutoscaling = new AtomicBoolean(false);
  private final AtomicBoolean gracefulPollShutdown = new AtomicBoolean(false);
  private final AtomicBoolean workerHeartbeats = new AtomicBoolean(false);

  public void setFromCapabilities(Capabilities capabilities) {
    if (capabilities.getPollerAutoscaling()) {
      pollerAutoscaling.set(true);
    }
    if (capabilities.getWorkerPollCompleteOnShutdown()) {
      gracefulPollShutdown.set(true);
    }
  }

  public boolean isPollerAutoscaling() {
    return pollerAutoscaling.get();
  }

  public boolean isGracefulPollShutdown() {
    return gracefulPollShutdown.get();
  }

  public void setGracefulPollShutdown(boolean value) {
    gracefulPollShutdown.set(value);
  }

  public boolean isWorkerHeartbeats() {
    return workerHeartbeats.get();
  }

  public void setWorkerHeartbeats(boolean value) {
    workerHeartbeats.set(value);
  }
}
