package io.temporal.internal.activity;

import com.uber.m3.tally.Scope;

public interface ActivityExecutionContextFactory {
  InternalActivityExecutionContext createContext(
      ActivityInfoInternal info, Object activity, Scope metricsScope);

  /**
   * Removes a context for a currently running activity identified by task token and optionally
   * requests cancellation.
   *
   * @return true if the activity was found and cleaned up.
   */
  boolean cleanupContext(byte[] taskToken, boolean cancel);
}
