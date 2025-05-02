package io.temporal.internal.nexus;

import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;

public class RootNexusOperationOutboundCallsInterceptor
    implements NexusOperationOutboundCallsInterceptor {
  private final Scope scope;
  private final WorkflowClient client;

  RootNexusOperationOutboundCallsInterceptor(Scope scope, WorkflowClient client) {
    this.scope = scope;
    this.client = client;
  }

  @Override
  public Scope getMetricsScope() {
    return scope;
  }

  @Override
  public WorkflowClient getWorkflowClient() {
    return client;
  }
}
