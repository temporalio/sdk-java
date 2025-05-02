package io.temporal.nexus;

import io.nexusrpc.handler.*;
import io.nexusrpc.handler.OperationHandler;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.WorkflowOptions;

/**
 * WorkflowRunOperation can be used to map a workflow run to a Nexus operation
 *
 * <h3>Pre-release feature: Attaching multiple Nexus callers to an underlying handler Workflow:</h3>
 *
 * <p>{@link WorkflowOptions#getWorkflowIdConflictPolicy()} is by default set to fail if a workflow
 * is already running. That is, if a caller executes another operation that starts the same
 * workflow, it will fail. You can set it to {@link
 * WorkflowIdConflictPolicy#WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING} to attach the caller's
 * callback to the existing running workflow. This way, all attached callers will be notified when
 * the workflow completes.
 */
public final class WorkflowRunOperation {
  /**
   * Maps a workflow method to an {@link io.nexusrpc.handler.OperationHandler}.
   *
   * @param startMethod returns the workflow method reference to call
   * @return Operation handler to be used as an {@link OperationImpl}
   */
  public static <T, R> OperationHandler<T, R> fromWorkflowMethod(
      WorkflowMethodFactory<T, R> startMethod) {
    return new WorkflowRunOperationImpl<>(
        (OperationContext context, OperationStartDetails details, T input) ->
            WorkflowHandle.fromWorkflowMethod(startMethod.apply(context, details, input), input));
  }

  /**
   * Maps a workflow handle to an {@link io.nexusrpc.handler.OperationHandler}.
   *
   * @param handleFactory returns the workflow handle that will be mapped to the call
   * @return Operation handler to be used as an {@link OperationImpl}
   */
  public static <T, R> OperationHandler<T, R> fromWorkflowHandle(
      WorkflowHandleFactory<T, R> handleFactory) {
    return new WorkflowRunOperationImpl<>(handleFactory);
  }

  /** Prohibits instantiation. */
  private WorkflowRunOperation() {}
}
