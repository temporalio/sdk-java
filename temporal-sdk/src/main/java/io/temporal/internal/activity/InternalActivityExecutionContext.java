package io.temporal.internal.activity;

import io.temporal.activity.ActivityExecutionContext;

/**
 * Internal context object passed to an Activity implementation, providing more internal details
 * than the user facing {@link ActivityExecutionContext}.
 */
public interface InternalActivityExecutionContext extends ActivityExecutionContext {
  /** Get the latest value of {@link ActivityExecutionContext#heartbeat(Object)}. */
  Object getLastHeartbeatValue();
}
