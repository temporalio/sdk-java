package io.temporal.internal.worker;

import io.temporal.api.namespace.v1.NamespaceInfo.Capabilities;
import io.temporal.api.namespace.v1.NamespaceInfo.Limits;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
  private final AtomicBoolean workflowTaskCompletionPagination = new AtomicBoolean(false);
  private final AtomicLong workflowTaskCompletionSizeLimit = new AtomicLong(0);

  public void setFromCapabilities(Capabilities capabilities) {
    if (capabilities.getPollerAutoscalingAutoEnroll()) {
      pollerAutoscalingAutoEnroll.set(true);
    }
    if (capabilities.getPollerAutoscaling()) {
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
    if (capabilities.getWorkflowTaskCompletionPagination()) {
      workflowTaskCompletionPagination.set(true);
    }
  }

  public void setFromLimits(Limits limits) {
    workflowTaskCompletionSizeLimit.set(limits.getWorkflowTaskCompletionSizeLimitError());
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

  public boolean isWorkflowTaskCompletionPagination() {
    return workflowTaskCompletionPagination.get();
  }

  public void setWorkflowTaskCompletionPagination(boolean value) {
    workflowTaskCompletionPagination.set(value);
  }

  /**
   * The namespace's limit on the recombined size in bytes of a single workflow task completion, or
   * 0 when the namespace advertises no explicit limit.
   */
  public long getWorkflowTaskCompletionSizeLimit() {
    return workflowTaskCompletionSizeLimit.get();
  }

  public void setWorkflowTaskCompletionSizeLimit(long value) {
    workflowTaskCompletionSizeLimit.set(value);
  }
}
