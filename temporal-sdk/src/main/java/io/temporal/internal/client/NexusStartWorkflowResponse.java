package io.temporal.internal.client;

import io.temporal.api.common.v1.WorkflowExecution;

public final class NexusStartWorkflowResponse {
  private final WorkflowExecution workflowExecution;
  private final String operationToken;

  public NexusStartWorkflowResponse(WorkflowExecution workflowExecution, String operationToken) {
    this.workflowExecution = workflowExecution;
    this.operationToken = operationToken;
  }

  public String getOperationToken() {
    return operationToken;
  }

  public WorkflowExecution getWorkflowExecution() {
    return workflowExecution;
  }
}
