package io.temporal.internal.activity;

import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;

public class LocalActivityExecutionContextFactoryImpl implements ActivityExecutionContextFactory {
  private final WorkflowClient client;

  public LocalActivityExecutionContextFactoryImpl(WorkflowClient client) {
    this.client = client;
  }

  @Override
  public InternalActivityExecutionContext createContext(
      ActivityInfoInternal info, Object activity, Scope metricsScope) {
    return new LocalActivityExecutionContextImpl(client, activity, info, metricsScope);
  }
}
