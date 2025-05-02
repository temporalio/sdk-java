package io.temporal.internal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Functions;

/**
 * From OOP point of view, there is no reason for this interface not to extend {@link
 * WorkflowClient}. It's not extending only to make sure that the calls that can come through a
 * user-supplied WorkflowClient are made through that entity whenever possible instead of the entity
 * obtained by {@link WorkflowClient#getInternal()}. This will make sure DI/AOP frameworks or user's
 * wrapping code implemented in WorkflowClient proxy or adapter gets executed when possible and
 * {@link WorkflowClient#getInternal()} is used only for internal functionality.
 */
public interface WorkflowClientInternal {
  void registerWorkerFactory(WorkerFactory workerFactory);

  void deregisterWorkerFactory(WorkerFactory workerFactory);

  WorkflowExecution startNexus(NexusStartWorkflowRequest request, Functions.Proc workflow);
}
