package io.temporal.internal.activity;

import com.uber.m3.tally.Scope;

public interface ActivityExecutionContextFactory {
  InternalActivityExecutionContext createContext(
      ActivityInfoInternal info, Object activity, Scope metricsScope);

  /**
   * Requests cancellation for a currently running activity identified by task token.
   *
   * @return true if the activity was found and marked canceled.
   */
  default boolean requestCancel(byte[] taskToken) {
    return false;
  }
}
