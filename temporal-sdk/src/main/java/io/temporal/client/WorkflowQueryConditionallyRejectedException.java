package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.QueryRejectCondition;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;

/**
 * If workflow gets rejected based on {@link QueryRejectCondition} specified on {@link
 * WorkflowClientOptions#getQueryRejectCondition()}
 */
public final class WorkflowQueryConditionallyRejectedException
    extends WorkflowQueryRejectedException {

  private final QueryRejectCondition queryRejectCondition;
  private final WorkflowExecutionStatus workflowExecutionStatus;

  public WorkflowQueryConditionallyRejectedException(
      WorkflowExecution execution,
      String workflowType,
      QueryRejectCondition queryRejectCondition,
      WorkflowExecutionStatus workflowExecutionStatus,
      Throwable cause) {
    super(execution, workflowType, cause);
    this.queryRejectCondition = queryRejectCondition;
    this.workflowExecutionStatus = workflowExecutionStatus;
  }

  public QueryRejectCondition getQueryRejectCondition() {
    return queryRejectCondition;
  }

  public WorkflowExecutionStatus getWorkflowExecutionStatus() {
    return workflowExecutionStatus;
  }
}
