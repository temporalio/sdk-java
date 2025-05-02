package io.temporal.internal.activity;

import com.uber.m3.tally.Scope;

public interface ActivityExecutionContextFactory {
  InternalActivityExecutionContext createContext(
      ActivityInfoInternal info, Object activity, Scope metricsScope);
}
