package io.temporal.internal.sync;

import io.temporal.workflow.NexusOperationExecution;
import io.temporal.workflow.NexusOperationHandle;
import io.temporal.workflow.Promise;

public class NexusOperationHandleImpl<R> implements NexusOperationHandle<R> {
  Promise<NexusOperationExecution> operationExecution;
  Promise<R> result;

  public NexusOperationHandleImpl(
      Promise<NexusOperationExecution> operationExecution, Promise<R> result) {
    this.operationExecution = operationExecution;
    this.result = result;
  }

  @Override
  public Promise<NexusOperationExecution> getExecution() {
    return operationExecution;
  }

  @Override
  public Promise<R> getResult() {
    return result;
  }
}
