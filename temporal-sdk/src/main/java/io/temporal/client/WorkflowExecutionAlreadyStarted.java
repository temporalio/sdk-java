package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;

/**
 * This exception is thrown in the following cases:
 * <li>
 *
 *     <ul>
 *       Workflow with the same WorkflowId is currently running.
 * </ul>
 *
 * <ul>
 *   There is a closed workflow with the same ID and the {@link
 *   WorkflowOptions#getWorkflowIdReusePolicy()} is {@link
 *   io.temporal.api.enums.v1.WorkflowIdReusePolicy#WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE}.
 * </ul>
 *
 * <ul>
 *   There is successfully closed workflow with the same ID and the {@link
 *   WorkflowOptions#getWorkflowIdReusePolicy()} is {@link
 *   io.temporal.api.enums.v1.WorkflowIdReusePolicy#WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY}.
 * </ul>
 *
 * <ul>
 *   Method annotated with {@link io.temporal.workflow.WorkflowMethod} is called <i>more than
 *   once</i> on a stub created through {@link
 *   io.temporal.workflow.Workflow#newChildWorkflowStub(Class)} and the {@link
 *   WorkflowOptions#getWorkflowIdReusePolicy()} is {@link
 *   io.temporal.api.enums.v1.WorkflowIdReusePolicy#WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE}
 * </ul>
 */
public final class WorkflowExecutionAlreadyStarted extends WorkflowException {
  public WorkflowExecutionAlreadyStarted(
      WorkflowExecution execution, String workflowType, Throwable cause) {
    super(execution, workflowType, cause);
  }
}
