package io.temporal.internal.nexus;

import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.client.StartActivityOptions;
import io.temporal.common.Experimental;
import io.temporal.common.interceptors.Header;
import io.temporal.internal.client.NexusStartActivityRequest;
import io.temporal.internal.client.NexusStartActivityResponse;
import java.util.List;
import java.util.function.Function;

/**
 * Shared helper for starting an activity from a Nexus operation and (in future) attaching links to
 * the operation context. Mirrors {@link NexusStartWorkflowHelper} for activities.
 */
@Experimental
public class NexusStartActivityHelper {

  /**
   * Starts an activity via the provided invoker function and returns the response.
   *
   * <p>The link-attachment block is intentionally a no-op in this revision; see the TODO inside.
   *
   * @param ctx the operation context (link attachment is deferred — see TODO)
   * @param details the operation start details containing requestId, callback, links
   * @param activityType the activity type name
   * @param args the activity arguments
   * @param options the activity scheduling options (must include task queue, ID)
   * @param header the propagated header
   * @param invoker function that starts the activity given a {@link NexusStartActivityRequest}
   * @return the {@link NexusStartActivityResponse} containing the activity ID and operation token
   */
  public static NexusStartActivityResponse startActivityAndAttachLinks(
      OperationContext ctx,
      OperationStartDetails details,
      String activityType,
      List<Object> args,
      StartActivityOptions options,
      Header header,
      Function<NexusStartActivityRequest, NexusStartActivityResponse> invoker) {
    InternalNexusOperationContext nexusCtx = CurrentNexusOperationContext.get();

    NexusStartActivityRequest nexusRequest =
        new NexusStartActivityRequest(
            details.getRequestId(),
            details.getCallbackUrl(),
            details.getCallbackHeaders(),
            nexusCtx.getTaskQueue(),
            details.getLinks(),
            activityType,
            args,
            options,
            header);

    NexusStartActivityResponse response = invoker.apply(nexusRequest);

    // TODO: Attach activity-event link when server-side activity link support is available.
    // No StartActivityResponseLink analog exists and there is no verified activity-event link path
    // in LinkConverter. Do NOT copy the synthetic workflow-event link fabrication from
    // NexusStartWorkflowHelper; Nexus operations function correctly without diagnostic links.

    return response;
  }

  private NexusStartActivityHelper() {}
}
