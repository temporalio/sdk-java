package io.temporal.nexus;

import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.workflow.Functions;
import javax.annotation.Nullable;

/**
 * Function interface for {@link WorkflowRunOperation#fromWorkflowMethod(WorkflowMethodFactory)}
 * representing the workflow method to invoke for every operation call.
 */
@FunctionalInterface
public interface WorkflowMethodFactory<T, R> {
  /**
   * Invoked every operation start call and expected to return a workflow method reference to a
   * proxy created through {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)} using the
   * provided {@link WorkflowClient} form {@link Nexus#getOperationContext()}.
   */
  @Nullable
  Functions.Func1<T, R> apply(OperationContext context, OperationStartDetails details, T input);
}
