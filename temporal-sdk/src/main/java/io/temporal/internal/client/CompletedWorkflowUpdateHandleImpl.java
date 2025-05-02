package io.temporal.internal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.client.WorkflowUpdateHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class CompletedWorkflowUpdateHandleImpl<T> implements WorkflowUpdateHandle<T> {

  private final String id;
  private final WorkflowExecution execution;
  private final WorkflowUpdateException exception;
  private final T result;

  public CompletedWorkflowUpdateHandleImpl(String id, WorkflowExecution execution, T result) {
    this.id = id;
    this.execution = execution;
    this.result = result;
    this.exception = null;
  }

  public CompletedWorkflowUpdateHandleImpl(
      String id, WorkflowExecution execution, WorkflowUpdateException ex) {
    this.id = id;
    this.execution = execution;
    this.exception = ex;
    this.result = null;
  }

  @Override
  public WorkflowExecution getExecution() {
    return execution;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public T getResult() {
    if (exception != null) {
      throw exception;
    }
    return result;
  }

  @Override
  public T getResult(long timeout, TimeUnit unit) {
    return getResult();
  }

  @Override
  public CompletableFuture<T> getResultAsync() {
    if (exception != null) {
      CompletableFuture<T> result = new CompletableFuture<>();
      result.completeExceptionally(exception);
      return result;
    }
    return CompletableFuture.completedFuture(result);
  }

  @Override
  public CompletableFuture<T> getResultAsync(long timeout, TimeUnit unit) {
    return getResultAsync();
  }
}
