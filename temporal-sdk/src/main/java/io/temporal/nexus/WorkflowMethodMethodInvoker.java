package io.temporal.nexus;

import io.temporal.internal.client.NexusStartWorkflowRequest;
import io.temporal.internal.client.NexusStartWorkflowResponse;
import io.temporal.internal.client.WorkflowClientInternal;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.workflow.Functions;

class WorkflowMethodMethodInvoker implements WorkflowHandleInvoker {
  private final Functions.Proc workflow;

  public WorkflowMethodMethodInvoker(Functions.Proc workflow) {
    this.workflow = workflow;
  }

  @Override
  public NexusStartWorkflowResponse invoke(NexusStartWorkflowRequest request) {
    InternalNexusOperationContext nexusCtx = CurrentNexusOperationContext.get();
    return ((WorkflowClientInternal) nexusCtx.getWorkflowClient().getInternal())
        .startNexus(request, workflow);
  }
}
