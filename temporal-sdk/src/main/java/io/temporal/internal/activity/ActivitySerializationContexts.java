package io.temporal.internal.activity;

import io.temporal.activity.ActivityInfo;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.SerializationContext;

/** The serialization contexts that the payloads of an activity task are encoded with. */
public final class ActivitySerializationContexts {

  /** The context of the activity's input and headers. */
  public static ActivitySerializationContext activity(String namespace, ActivityInfo info) {
    return new ActivitySerializationContext(
        namespace,
        info.getWorkflowId(),
        info.getWorkflowType(),
        info.getActivityType(),
        info.getActivityTaskQueue(),
        info.isLocal());
  }

  /**
   * The context of the activity's result, failure and heartbeat details. That is the handler's
   * Nexus context for a standalone activity started from a Nexus operation handler, and the
   * activity context otherwise.
   */
  public static SerializationContext result(String namespace, ActivityInfo info) {
    NexusSerializationContext nexusContext = info.getNexusSerializationContext();
    return nexusContext != null ? nexusContext : activity(namespace, info);
  }

  private ActivitySerializationContexts() {}
}
