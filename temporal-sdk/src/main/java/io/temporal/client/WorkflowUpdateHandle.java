package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * WorkflowUpdateHandle is a handle to an update workflow execution request that can be used to get
 * the status of that update request.
 */
public interface WorkflowUpdateHandle<T> {
  /**
   * Gets the workflow execution this update request was sent to.
   *
   * @return the workflow execution this update was sent to.
   */
  WorkflowExecution getExecution();

  /**
   * Gets the unique ID of this update.
   *
   * @return the updates ID.
   */
  String getId();

  /**
   * Returns the result of the workflow update.
   *
   * @return the result of the workflow update
   * @throws WorkflowUpdateException if the update was rejected or failed by the workflow.
   */
  T getResult();

  /**
   * Returns the result of the workflow update.
   *
   * @param timeout maximum time to wait and perform the background long polling
   * @param unit unit of timeout
   * @throws WorkflowUpdateTimeoutOrCancelledException if the timeout is reached.
   * @throws WorkflowUpdateException if the update was rejected or failed by the workflow.
   * @return the result of the workflow update
   */
  T getResult(long timeout, TimeUnit unit);

  /**
   * Returns a {@link CompletableFuture} with the update workflow execution request result,
   * potentially waiting for the update to complete.
   *
   * @return future completed with the result of the update or an exception
   */
  CompletableFuture<T> getResultAsync();

  /**
   * Returns a {@link CompletableFuture} with the update workflow execution request result,
   * potentially waiting for the update to complete.
   *
   * @param timeout maximum time to wait and perform the background long polling
   * @param unit unit of timeout
   * @return future completed with the result of the update or an exception
   */
  CompletableFuture<T> getResultAsync(long timeout, TimeUnit unit);
}
