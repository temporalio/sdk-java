package io.temporal.nexus;

import io.nexusrpc.handler.*;
import io.nexusrpc.handler.OperationHandler;
import io.temporal.client.WorkflowClient;
import io.temporal.internal.client.NexusStartWorkflowResponse;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.NexusStartWorkflowHelper;
import io.temporal.internal.nexus.OperationTokenUtil;

class WorkflowRunOperationImpl<T, R> implements OperationHandler<T, R> {
  private final WorkflowHandleFactory<T, R> handleFactory;

  WorkflowRunOperationImpl(WorkflowHandleFactory<T, R> handleFactory) {
    this.handleFactory = handleFactory;
  }

  @Override
  public OperationStartResult<R> start(
      OperationContext ctx, OperationStartDetails operationStartDetails, T input) {
    WorkflowHandle<R> handle = handleFactory.apply(ctx, operationStartDetails, input);

    NexusStartWorkflowResponse response =
        NexusStartWorkflowHelper.startWorkflowAndAttachLinks(
            ctx, operationStartDetails, request -> handle.getInvoker().invoke(request));

    return OperationStartResult.<R>newAsyncBuilder(response.getOperationToken()).build();
  }

  @Override
  public void cancel(
      OperationContext operationContext, OperationCancelDetails operationCancelDetails) {
    String workflowId;
    try {
      workflowId =
          OperationTokenUtil.loadWorkflowIdFromOperationToken(
              operationCancelDetails.getOperationToken());
    } catch (IllegalArgumentException e) {
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST, "failed to parse operation token", e);
    }

    WorkflowClient client = CurrentNexusOperationContext.get().getWorkflowClient();
    client.newUntypedWorkflowStub(workflowId).cancel();
  }
}
