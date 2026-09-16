package io.temporal.internal.common;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.client.NexusStartWorkflowResponse;
import java.lang.reflect.Type;

public class NexusWorkflowStarter {
  private final WorkflowStub workflowStub;
  private final String operationToken;

  public NexusWorkflowStarter(WorkflowStub workflowStub, String operationToken) {
    this.workflowStub = workflowStub;
    this.operationToken = operationToken;
  }

  public NexusStartWorkflowResponse start(Object... args) {
    WorkflowExecution workflowExecution = workflowStub.start(args);
    return new NexusStartWorkflowResponse(workflowExecution, operationToken);
  }

  public NexusStartWorkflowResponse start(Type[] argTypes, Object... args) {
    WorkflowExecution workflowExecution = workflowStub.start(argTypes, args);
    return new NexusStartWorkflowResponse(workflowExecution, operationToken);
  }
}
