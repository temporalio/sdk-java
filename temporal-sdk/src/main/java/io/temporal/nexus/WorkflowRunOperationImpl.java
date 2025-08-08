package io.temporal.nexus;

import static io.temporal.internal.common.LinkConverter.workflowEventToNexusLink;
import static io.temporal.internal.common.NexusUtil.nexusProtoLinkToLink;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationInfo;
import io.nexusrpc.OperationState;
import io.nexusrpc.OperationStillRunningException;
import io.nexusrpc.handler.*;
import io.nexusrpc.handler.OperationHandler;
import io.temporal.api.common.v1.Link;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.client.NexusStartWorkflowRequest;
import io.temporal.internal.client.NexusStartWorkflowResponse;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.internal.nexus.OperationTokenUtil;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
  @SuppressWarnings("unchecked")
  public R fetchResult(
      OperationContext operationContext, OperationFetchResultDetails operationFetchResultDetails)
      throws OperationStillRunningException, OperationException {
    String workflowId = extractWorkflowIdFromToken(operationFetchResultDetails.getOperationToken());
    WorkflowClient client = CurrentNexusOperationContext.get().getWorkflowClient();

    Type outputType = CurrentNexusOperationContext.get().getOperationDefinition().getOutputType();
    try {
      return (R)
          client
              .newUntypedWorkflowStub(workflowId)
              .getResult(
                  operationFetchResultDetails.getTimeout().get(ChronoUnit.SECONDS),
                  TimeUnit.SECONDS,
                  com.google.common.reflect.TypeToken.of(outputType).getRawType(),
                  outputType);
    } catch (TimeoutException te) {
      throw new OperationStillRunningException();
    } catch (WorkflowNotFoundException e) {
      throw new HandlerException(HandlerException.ErrorType.NOT_FOUND, e);
    } catch (WorkflowException we) {
      if (we.getCause() instanceof CanceledFailure) {
        throw OperationException.canceled(we.getCause());
      } else {
        throw OperationException.failure(we.getCause());
      }
    }
  }

  @Override
  public OperationInfo fetchInfo(
      OperationContext operationContext, OperationFetchInfoDetails operationFetchInfoDetails) {
    String workflowId = extractWorkflowIdFromToken(operationFetchInfoDetails.getOperationToken());
    try {
      WorkflowClient client = CurrentNexusOperationContext.get().getWorkflowClient();
      WorkflowExecutionDescription description =
          client.newUntypedWorkflowStub(workflowId).describe();
      OperationState state = null;
      switch (description.getStatus()) {
        case WORKFLOW_EXECUTION_STATUS_RUNNING:
        case WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW:
          // WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW really shouldn't be possible here,
          // but we handle it gracefully by treating it as RUNNING.
          state = OperationState.RUNNING;
          break;
        case WORKFLOW_EXECUTION_STATUS_COMPLETED:
          state = OperationState.SUCCEEDED;
          break;
        case WORKFLOW_EXECUTION_STATUS_CANCELED:
          state = OperationState.CANCELED;
          break;
        case WORKFLOW_EXECUTION_STATUS_FAILED:
        case WORKFLOW_EXECUTION_STATUS_TIMED_OUT:
        case WORKFLOW_EXECUTION_STATUS_TERMINATED:
          state = OperationState.FAILED;
          break;
        default:
          throw new HandlerException(
              HandlerException.ErrorType.INTERNAL,
              new IllegalArgumentException("Unknown workflow status: " + description.getStatus()));
      }
      return OperationInfo.newBuilder()
          .setState(state)
          .setToken(operationFetchInfoDetails.getOperationToken())
          .build();
    } catch (WorkflowNotFoundException e) {
      throw new HandlerException(HandlerException.ErrorType.NOT_FOUND, e);
    }
  }

  @Override
  public void cancel(
      OperationContext operationContext, OperationCancelDetails operationCancelDetails) {
    try {
      String workflowId = extractWorkflowIdFromToken(operationCancelDetails.getOperationToken());
      WorkflowClient client = CurrentNexusOperationContext.get().getWorkflowClient();
      client.newUntypedWorkflowStub(workflowId).cancel();
    } catch (WorkflowNotFoundException e) {
      throw new HandlerException(HandlerException.ErrorType.NOT_FOUND, e);
    }
  }

  private String extractWorkflowIdFromToken(String operationToken) {
    String workflowId;
    try {
      workflowId = OperationTokenUtil.loadWorkflowIdFromOperationToken(operationToken);
    } catch (IllegalArgumentException e) {
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST,
          new IllegalArgumentException("failed to parse operation token", e));
    }
    return workflowId;
  }
}
