package io.temporal.nexus;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.internal.client.NexusStartWorkflowRequest;
import io.temporal.internal.client.WorkflowClientInternal;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.workflow.Functions;

class WorkflowMethodMethodInvoker implements WorkflowHandleInvoker {
  private Functions.Proc workflow;

  public WorkflowMethodMethodInvoker(Functions.Proc workflow) {
    this.workflow = workflow;
  }

  @Override
  public WorkflowExecution invoke(NexusStartWorkflowRequest request) {
    InternalNexusOperationContext nexusCtx = CurrentNexusOperationContext.get();
    return ((WorkflowClientInternal) nexusCtx.getWorkflowClient().getInternal())
        .startNexus(request, workflow);
  }
}
