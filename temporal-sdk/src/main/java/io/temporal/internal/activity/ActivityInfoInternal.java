package io.temporal.internal.activity;

import io.temporal.activity.ActivityInfo;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.internal.nexus.PropagatedNexusSerializationContext;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.SerializationContext;
import io.temporal.workflow.Functions;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * An extension for {@link ActivityInfo} with information about the activity task that the current
 * activity is handling that should be available for Temporal SDK, but shouldn't be available or
 * exposed to Activity implementation code.
 */
interface ActivityInfoInternal extends ActivityInfo {
  /**
   * @return function shat should be triggered after activity completion with any outcome (success,
   *     failure, cancelling)
   */
  Functions.Proc getCompletionHandle();

  /**
   * @return input parameters of the activity execution
   */
  Optional<Payloads> getInput();

  /**
   * @return header that is passed with the activity execution
   */
  Optional<Header> getHeader();

  /**
   * @return serialization context of the Nexus operation handler that started this standalone
   *     activity, as propagated by the server, or null if the activity was not started by one
   */
  @Nullable
  PropagatedSerializationContext getPropagatedNexusSerializationContext();

  /**
   * The serialization context for the activity's input and heartbeat details, built from {@code
   * namespace} and {@code info}. Used even for a standalone activity started from a Nexus operation
   * handler, because these payloads are not returned to the Nexus caller.
   */
  static ActivitySerializationContext activitySerializationContext(
      String namespace, ActivityInfo info) {
    return new ActivitySerializationContext(
        namespace,
        info.getWorkflowId(),
        info.getWorkflowType(),
        info.getActivityType(),
        info.getActivityTaskQueue(),
        info.isLocal());
  }

  /**
   * The serialization context for the activity's result and failure.
   *
   * <p>A standalone activity started from a Nexus operation handler returns its result and failure
   * to the Nexus caller, so they use the handler's Nexus context, and the activity worker and the
   * clients reading the result agree on it. Other activities use {@link
   * #activitySerializationContext}.
   */
  static SerializationContext resultSerializationContext(String namespace, ActivityInfo info) {
    if (info instanceof ActivityInfoInternal) {
      PropagatedSerializationContext propagated =
          ((ActivityInfoInternal) info).getPropagatedNexusSerializationContext();
      if (propagated != null) {
        return PropagatedNexusSerializationContext.fromProto(propagated);
      }
    }
    return activitySerializationContext(namespace, info);
  }
}
