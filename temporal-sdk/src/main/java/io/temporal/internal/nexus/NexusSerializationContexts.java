package io.temporal.internal.nexus;

import io.temporal.api.nexus.v1.Request;
import io.temporal.payload.context.NexusSerializationContext;
import javax.annotation.Nullable;

/** The serialization context that the payloads of a Nexus task are encoded with. */
public final class NexusSerializationContexts {

  /**
   * The context of the endpoint, service and operation {@code request} names, or null when it names
   * no operation.
   *
   * <p>Note that servers before 1.30.0 do not report the endpoint the task was addressed to, so the
   * context is scoped by an empty endpoint there and does not agree with the caller's. Nexus
   * serialization context on the handler side requires server 1.30.0 or later.
   */
  public static @Nullable NexusSerializationContext forRequest(Request request) {
    switch (request.getVariantCase()) {
      case START_OPERATION:
        return new NexusSerializationContext(
            request.getEndpoint(),
            request.getStartOperation().getService(),
            request.getStartOperation().getOperation());
      case CANCEL_OPERATION:
        return new NexusSerializationContext(
            request.getEndpoint(),
            request.getCancelOperation().getService(),
            request.getCancelOperation().getOperation());
      default:
        return null;
    }
  }

  private NexusSerializationContexts() {}
}
