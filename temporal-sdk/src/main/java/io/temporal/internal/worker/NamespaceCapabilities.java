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
  private final AtomicBoolean pollerAutoscalingAutoEnroll = new AtomicBoolean(false);
  private final AtomicBoolean gracefulPollShutdown = new AtomicBoolean(false);
  private final AtomicBoolean workerHeartbeats = new AtomicBoolean(false);
  private final AtomicBoolean workerCommands = new AtomicBoolean(false);

  public void setFromCapabilities(Capabilities capabilities) {
    if (capabilities.getPollerAutoscalingAutoEnroll()) {
      pollerAutoscalingAutoEnroll.set(true);
    }
    // The auto-enroll capability implies the server speaks the full poller-scaling protocol, so
    // also enable pollerAutoscaling: it is the flag AsyncPoller passes to PollScaleReportHandle as
    // serverSupportsAutoscaling, which lets the poller count follow the server's scaling
    // suggestions.
    if (capabilities.getPollerAutoscaling() || capabilities.getPollerAutoscalingAutoEnroll()) {
      pollerAutoscaling.set(true);
    }
    if (capabilities.getWorkerPollCompleteOnShutdown()) {
      gracefulPollShutdown.set(true);
    }
    if (capabilities.getWorkerHeartbeats()) {
      workerHeartbeats.set(true);
    }
    if (capabilities.getWorkerCommands()) {
      workerCommands.set(true);
    }
  }

  public boolean isPollerAutoscaling() {
    return pollerAutoscaling.get();
  }

  public boolean isPollerAutoscalingAutoEnroll() {
    return pollerAutoscalingAutoEnroll.get();
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

  public boolean isWorkerCommands() {
    return workerCommands.get();
  }

  public void setWorkerCommands(boolean value) {
    workerCommands.set(value);
  }
}
