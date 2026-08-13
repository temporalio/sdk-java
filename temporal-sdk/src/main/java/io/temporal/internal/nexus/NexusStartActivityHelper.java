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

/** Shared helper for starting an activity from a Nexus operation. */
@Experimental
public class NexusStartActivityHelper {

  /**
   * Starts an activity via the provided invoker function and returns the response. The root
   * activity invoker records the link from {@code StartActivityExecutionResponse}; the Nexus task
   * handler attaches that link to the operation response.
   *
   * @param ctx the operation context
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

    return invoker.apply(nexusRequest);
  }

  private NexusStartActivityHelper() {}
}
