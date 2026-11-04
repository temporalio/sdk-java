package io.temporal.internal.nexus;

import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.payload.context.NexusSerializationContext;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Converts between {@link NexusSerializationContext} and the {@link PropagatedSerializationContext}
 * the server stores on executions started by a Nexus operation handler.
 *
 * <p>When a Nexus handler starts a workflow, sends a workflow update or starts a standalone
 * activity, the SDK sends the handler's serialization context with the request. The server stores
 * it and returns it to workers and to clients reading the execution's result, so the result and
 * failure are encoded and decoded with the same context. See {@link NexusSerializationContext} for
 * which payloads use it.
 */
public final class PropagatedNexusSerializationContext {

  /**
   * The serialization context of the Nexus operation being handled on the current thread, in the
   * form sent with start requests, or {@code null} if there is no Nexus task in scope or its
   * service and operation are not known yet.
   */
  public static @Nullable PropagatedSerializationContext current() {
    if (!CurrentNexusOperationContext.isNexusContext()) {
      return null;
    }
    NexusSerializationContext context =
        CurrentNexusOperationContext.get().getSerializationContext();
    return context != null ? toProto(context) : null;
  }

  public static @Nonnull PropagatedSerializationContext toProto(
      @Nonnull NexusSerializationContext context) {
    return PropagatedSerializationContext.newBuilder()
        .setEndpoint(context.getEndpoint())
        .setService(context.getService())
        .setOperation(context.getOperation())
        .build();
  }

  public static @Nonnull NexusSerializationContext fromProto(
      @Nonnull PropagatedSerializationContext context) {
    return new NexusSerializationContext(
        context.getEndpoint(), context.getService(), context.getOperation());
  }

  private PropagatedNexusSerializationContext() {}
}
