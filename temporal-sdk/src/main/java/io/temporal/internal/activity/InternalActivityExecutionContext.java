package io.temporal.internal.activity;

import io.temporal.activity.ActivityExecutionContext;

/**
 * Internal context object passed to an Activity implementation, providing more internal details
 * than the user facing {@link ActivityExecutionContext}.
 */
public interface InternalActivityExecutionContext extends ActivityExecutionContext {
  /** Get the latest value of {@link ActivityExecutionContext#heartbeat(Object)}. */
  Object getLastHeartbeatValue();

  /** Mark this context as returned for async completion. */
  void asyncCompletionStarted();

  /** Cancel any pending heartbeat and discard cached heartbeat details. */
  void cancelOutstandingHeartbeat();

  /**
   * Returns whether the activity task's outcome was already reported to the server, for example
   * because an oversized heartbeat failed it, so the activity's own result must not be sent.
   */
  boolean isTaskReported();
}
