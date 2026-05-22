package io.temporal.internal.nexus;

import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.Link;
import io.temporal.client.WorkflowClient;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;
import io.temporal.nexus.NexusOperationContext;
import io.temporal.nexus.NexusOperationInfo;
import java.util.Collections;
import java.util.List;

public class InternalNexusOperationContext {
  private final String namespace;
  private final String taskQueue;
  private final String endpoint;
  private final Scope metricScope;
  private final WorkflowClient client;
  NexusOperationOutboundCallsInterceptor outboundCalls;
  Link startWorkflowResponseLink;
  // Links extracted from the inbound Nexus task. Stored once at the task-handler boundary so the
  // workflow client (signal, signalWithStart) can attach them to outgoing requests via
  // SignalWorkflowExecutionRequest.links, matching the Go SDK's NexusOperationLinksKey ctx value.
  private List<Link> nexusOperationLinks = Collections.emptyList();
  // Backlink returned by SignalWorkflowExecutionResponse.link /
  // SignalWithStartWorkflowExecutionResponse.signal_link.
  // Populated by the workflow client and consumed by the task handler when building
  // StartOperationResponse, so the caller workflow gets a link pointing at the signal event on
  // the callee.
  private Link signalWorkflowResponseLink;

  public InternalNexusOperationContext(
      String namespace,
      String taskQueue,
      String endpoint,
      Scope metricScope,
      WorkflowClient client) {
    this.namespace = namespace;
    this.taskQueue = taskQueue;
    this.endpoint = endpoint;
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

  public String getEndpoint() {
    return endpoint;
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

  /**
   * Set the {@code common.v1.Link}s extracted from the inbound Nexus task so they can be attached
   * to any signal RPCs issued by the operation handler.
   */
  public void setNexusOperationLinks(List<Link> links) {
    this.nexusOperationLinks = links == null ? Collections.emptyList() : links;
  }

  /** Links from the inbound Nexus task; empty if none. Never null. */
  public List<Link> getNexusOperationLinks() {
    return nexusOperationLinks;
  }

  public void setSignalWorkflowResponseLink(Link link) {
    this.signalWorkflowResponseLink = link;
  }

  public Link getSignalWorkflowResponseLink() {
    return signalWorkflowResponseLink;
  }

  private class NexusOperationContextImpl implements NexusOperationContext {
    @Override
    public NexusOperationInfo getInfo() {
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
