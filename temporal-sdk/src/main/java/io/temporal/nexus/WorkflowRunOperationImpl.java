package io.temporal.nexus;

import static io.temporal.internal.common.LinkConverter.workflowEventToNexusLink;
import static io.temporal.internal.common.NexusUtil.nexusProtoLinkToLink;

import io.nexusrpc.OperationInfo;
import io.nexusrpc.handler.*;
import io.nexusrpc.handler.OperationHandler;
import io.temporal.api.common.v1.Link;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.internal.client.NexusStartWorkflowRequest;
import io.temporal.internal.client.NexusStartWorkflowResponse;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.internal.nexus.OperationTokenUtil;
import java.net.URISyntaxException;

class WorkflowRunOperationImpl<T, R> implements OperationHandler<T, R> {
  private final WorkflowHandleFactory<T, R> handleFactory;

  WorkflowRunOperationImpl(WorkflowHandleFactory<T, R> handleFactory) {
    this.handleFactory = handleFactory;
  }

  @Override
  public OperationStartResult<R> start(
      OperationContext ctx, OperationStartDetails operationStartDetails, T input) {
    InternalNexusOperationContext nexusCtx = CurrentNexusOperationContext.get();

    WorkflowHandle<R> handle = handleFactory.apply(ctx, operationStartDetails, input);

    NexusStartWorkflowRequest nexusRequest =
        new NexusStartWorkflowRequest(
            operationStartDetails.getRequestId(),
            operationStartDetails.getCallbackUrl(),
            operationStartDetails.getCallbackHeaders(),
            nexusCtx.getTaskQueue(),
            operationStartDetails.getLinks());

    NexusStartWorkflowResponse nexusStartWorkflowResponse =
        handle.getInvoker().invoke(nexusRequest);
    WorkflowExecution workflowExec = nexusStartWorkflowResponse.getWorkflowExecution();

    // If the start workflow response returned a link use it, otherwise
    // create the link information about the new workflow and return to the caller.
    Link.WorkflowEvent workflowEventLink =
        nexusCtx.getStartWorkflowResponseLink().hasWorkflowEvent()
            ? nexusCtx.getStartWorkflowResponseLink().getWorkflowEvent()
            : null;
    if (workflowEventLink == null) {
      workflowEventLink =
          Link.WorkflowEvent.newBuilder()
              .setNamespace(nexusCtx.getNamespace())
              .setWorkflowId(workflowExec.getWorkflowId())
              .setRunId(workflowExec.getRunId())
              .setEventRef(
                  Link.WorkflowEvent.EventReference.newBuilder()
                      .setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED))
              .build();
    }
    io.temporal.api.nexus.v1.Link nexusLink = workflowEventToNexusLink(workflowEventLink);
    // Attach the link to the operation result.
    OperationStartResult.Builder<R> result =
        OperationStartResult.newAsyncBuilder(nexusStartWorkflowResponse.getOperationToken());
    if (nexusLink != null) {
      try {
        ctx.addLinks(nexusProtoLinkToLink(nexusLink));
      } catch (URISyntaxException e) {
        // Not expected as the link is constructed by the SDK.
        throw new HandlerException(
            HandlerException.ErrorType.INTERNAL,
            new IllegalArgumentException("failed to parse URI", e));
      }
    }
    return result.build();
  }

  @Override
  public R fetchResult(
      OperationContext operationContext, OperationFetchResultDetails operationFetchResultDetails) {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public OperationInfo fetchInfo(
      OperationContext operationContext, OperationFetchInfoDetails operationFetchInfoDetails) {
    throw new UnsupportedOperationException("Not implemented");
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
          HandlerException.ErrorType.BAD_REQUEST,
          new IllegalArgumentException("failed to parse operation token", e));
    }

    WorkflowClient client = CurrentNexusOperationContext.get().getWorkflowClient();
    client.newUntypedWorkflowStub(workflowId).cancel();
  }
}
