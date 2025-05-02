package io.temporal.internal.client;

import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkflowTaskDispatchHandle;
import javax.annotation.Nullable;

class EagerWorkflowTaskDispatcher {
  private final WorkerFactoryRegistry workerFactories;

  public EagerWorkflowTaskDispatcher(WorkerFactoryRegistry workerFactories) {
    this.workerFactories = workerFactories;
  }

  @Nullable
  public WorkflowTaskDispatchHandle tryGetLocalDispatchHandler(
      WorkflowClientCallsInterceptor.WorkflowStartInput workflowStartInput) {
    for (WorkerFactory workerFactory : workerFactories.workerFactoriesRandomOrder()) {
      Worker worker = workerFactory.tryGetWorker(workflowStartInput.getOptions().getTaskQueue());
      if (worker != null) {
        WorkflowTaskDispatchHandle workflowTaskDispatchHandle = worker.reserveWorkflowExecutor();
        if (workflowTaskDispatchHandle != null) {
          return workflowTaskDispatchHandle;
        }
      }
    }
    return null;
  }
}
