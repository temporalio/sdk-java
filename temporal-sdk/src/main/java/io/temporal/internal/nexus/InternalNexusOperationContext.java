package io.temporal.internal.nexus;

import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.Link;
import io.temporal.client.WorkflowClient;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;
import io.temporal.nexus.NexusOperationContext;
import io.temporal.nexus.NexusOperationInfo;
import java.util.ArrayList;
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
  // SignalWorkflowExecutionRequest.links.
  private List<Link> nexusOperationLinks = Collections.emptyList();
  // Backlinks returned by outbound RPCs the operation handler issues (currently
  // SignalWorkflowExecutionResponse.link and SignalWithStartWorkflowExecutionResponse.signal_link;
  // future update/start variants attach the same way). One entry per outbound RPC that returned
  // a link. Drained by the task handler when building StartOperationResponse so each RPC the
  // handler issued gets a corresponding link on the caller workflow's history event.
  //
  // NOTE: this context is only safe for use from the single thread that runs the operation
  // handler (the Nexus task executor's thread). Handlers that spawn their own threads to issue
  // RPCs will not see the thread-local context, so the links from those RPCs will not propagate.
  private final List<Link> responseBacklinks = new ArrayList<>();

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
   * to RPCs issued by the operation handler.
   */
  public void setNexusOperationLinks(List<Link> links) {
    this.nexusOperationLinks = links == null ? Collections.emptyList() : links;
  }

  /** Links from the inbound Nexus task; empty if none. Never null. */
  public List<Link> getNexusOperationLinks() {
    return nexusOperationLinks;
  }

  /**
   * Append a backlink returned by an outbound RPC the operation handler issued (e.g. signal,
   * signalWithStart, and future update/start variants). The task handler drains the list when
   * building the operation's StartOperationResponse.
   */
  public void addBacklink(Link link) {
    if (link != null) {
      this.responseBacklinks.add(link);
    }
  }

  /** Backlinks from every outbound RPC the handler issued. Never null; may be empty. */
  public List<Link> getBacklinks() {
    return responseBacklinks;
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
