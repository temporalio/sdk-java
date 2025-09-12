package io.temporal.internal.nexus;

import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.Link;
import io.temporal.client.WorkflowClient;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;
import io.temporal.nexus.NexusInfo;
import io.temporal.nexus.NexusOperationContext;

public class InternalNexusOperationContext {
  private final String namespace;
  private final String taskQueue;
  private final Scope metricScope;
  private final WorkflowClient client;
  NexusOperationOutboundCallsInterceptor outboundCalls;
  Link startWorkflowResponseLink;

  public InternalNexusOperationContext(
      String namespace, String taskQueue, Scope metricScope, WorkflowClient client) {
    this.namespace = namespace;
    this.taskQueue = taskQueue;
    this.metricScope = metricScope;
    this.client = client;
  }

  public Scope getMetricsScope() {
    return metricScope;
  }

  public WorkflowClient getWorkflowClient() {
    return client;
  }

  public String getTaskQueue() {
    return taskQueue;
  }

  public String getNamespace() {
    return namespace;
  }

  public void setOutboundInterceptor(NexusOperationOutboundCallsInterceptor outboundCalls) {
    this.outboundCalls = outboundCalls;
  }

  public NexusOperationContext getUserFacingContext() {
    if (outboundCalls == null) {
      throw new IllegalStateException("Outbound interceptor is not set");
    }
    return new NexusOperationContextImpl();
  }

  public void setStartWorkflowResponseLink(Link link) {
    this.startWorkflowResponseLink = link;
  }

  public Link getStartWorkflowResponseLink() {
    return startWorkflowResponseLink;
  }

  private class NexusOperationContextImpl implements NexusOperationContext {
    @Override
    public NexusInfo getInfo() {
      return outboundCalls.getInfo();
    }

    @Override
    public Scope getMetricsScope() {
      return outboundCalls.getMetricsScope();
    }

    @Override
    public WorkflowClient getWorkflowClient() {
      return outboundCalls.getWorkflowClient();
    }
  }
}
