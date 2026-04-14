package io.temporal.internal.nexus;

import static io.temporal.internal.common.LinkConverter.workflowEventToNexusLink;
import static io.temporal.internal.common.NexusUtil.nexusProtoLinkToLink;

import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.api.common.v1.Link;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.internal.client.NexusStartWorkflowRequest;
import io.temporal.internal.client.NexusStartWorkflowResponse;
import java.net.URISyntaxException;
import java.util.function.Function;

/**
 * Shared helper for starting a workflow from a Nexus operation and attaching workflow links to the
 * operation context. Used by both {@code WorkflowRunOperationImpl} and {@code TemporalNexusClient}.
 */
public class NexusStartWorkflowHelper {

  /**
   * Starts a workflow via the provided invoker function, attaches workflow links to the operation
   * context, and returns the response.
   *
   * @param ctx the operation context (links will be attached as a side-effect)
   * @param details the operation start details containing requestId, callback, links
   * @param invoker function that starts the workflow given a {@link NexusStartWorkflowRequest}
   * @return the {@link NexusStartWorkflowResponse} containing the operation token and workflow
   *     execution
   */
  public static NexusStartWorkflowResponse startWorkflowAndAttachLinks(
      OperationContext ctx,
      OperationStartDetails details,
      Function<NexusStartWorkflowRequest, NexusStartWorkflowResponse> invoker) {
    InternalNexusOperationContext nexusCtx = CurrentNexusOperationContext.get();

    NexusStartWorkflowRequest nexusRequest =
        new NexusStartWorkflowRequest(
            details.getRequestId(),
            details.getCallbackUrl(),
            details.getCallbackHeaders(),
            nexusCtx.getTaskQueue(),
            details.getLinks());

    NexusStartWorkflowResponse response = invoker.apply(nexusRequest);
    WorkflowExecution workflowExec = response.getWorkflowExecution();

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
    if (nexusLink != null) {
      try {
        ctx.addLinks(nexusProtoLinkToLink(nexusLink));
      } catch (URISyntaxException e) {
        throw new HandlerException(HandlerException.ErrorType.INTERNAL, "failed to parse URI", e);
      }
    }

    return response;
  }

  private NexusStartWorkflowHelper() {}
}
