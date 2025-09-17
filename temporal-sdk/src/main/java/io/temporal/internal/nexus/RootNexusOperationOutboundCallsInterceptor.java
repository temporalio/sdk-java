package io.temporal.internal.nexus;

import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;
import io.temporal.nexus.NexusOperationInfo;

public class RootNexusOperationOutboundCallsInterceptor
    implements NexusOperationOutboundCallsInterceptor {
  private final Scope scope;
  private final WorkflowClient client;
  private final NexusOperationInfo nexusInfo;

  RootNexusOperationOutboundCallsInterceptor(
      Scope scope, WorkflowClient client, NexusOperationInfo nexusInfo) {
    this.scope = scope;
    this.client = client;
    this.nexusInfo = nexusInfo;
  }

  @Override
  public NexusOperationInfo getInfo() {
    return nexusInfo;
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
