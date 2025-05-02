package io.temporal.nexus;

import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.client.WorkflowClient;
import javax.annotation.Nullable;

/**
 * Function interface for {@link WorkflowRunOperation#fromWorkflowHandle(WorkflowHandleFactory)}
 * representing the workflow to associate with each operation call.
 */
@FunctionalInterface
public interface WorkflowHandleFactory<T, R> {
  /**
   * Invoked every operation start call and expected to return a workflow handle to a workflow stub
   * created with the {@link WorkflowClient} provided by {@link
   * NexusOperationContext#getWorkflowClient()}.
   */
  @Nullable
  WorkflowHandle<R> apply(OperationContext context, OperationStartDetails details, T input);
}
