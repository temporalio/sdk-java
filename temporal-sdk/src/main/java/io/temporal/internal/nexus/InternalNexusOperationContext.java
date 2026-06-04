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
import javax.annotation.Nonnull;

public class InternalNexusOperationContext {
  private final String namespace;
  private final String taskQueue;
  private final String endpoint;
  private final Scope metricScope;
  private final WorkflowClient client;
  NexusOperationOutboundCallsInterceptor outboundCalls;
  // Links extracted from the inbound Nexus task. Stored once at the task-handler boundary so the
  // workflow client can attach them to the outgoing requests it issues (e.g. signal,
  // signalWithStart) via the request's links field.
  private List<Link> nexusOperationLinks = Collections.emptyList();
  // Backlinks returned by outbound RPCs the operation handler issues (such as
  // SignalWorkflowExecutionResponse.link or SignalWithStartWorkflowExecutionResponse.signal_link).
  // One entry per outbound RPC that returned a link. Drained
  // by the task handler when building StartOperationResponse so each RPC the handler issued gets a
  // corresponding link on the caller workflow's history event.
  //
  // This context is only safe for use from the single thread that runs the operation handler (the
  // Nexus task executor's thread); the backing ArrayList is not synchronized. Handlers must not
  // mutate it from other threads.
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

  /**
   * Set the {@code common.v1.Link}s extracted from the inbound Nexus task so they can be attached
   * to RPCs issued by the operation handler.
   */
  public void setNexusOperationLinks(List<Link> links) {
    this.nexusOperationLinks = links == null ? Collections.emptyList() : links;
  }

  /** Links from the inbound Nexus task; empty if none. */
  public @Nonnull List<Link> getNexusOperationLinks() {
    return Collections.unmodifiableList(nexusOperationLinks);
  }

  /**
   * Append a backlink returned by an outbound RPC the operation handler issued (e.g. signal,
   * signalWithStart, etc). The task handler drains the list when building the operation's
   * StartOperationResponse.
   */
  public void addBacklink(Link link) {
    if (link != null) {
      this.responseBacklinks.add(link);
    }
  }

  /**
   * Backlinks from every outbound RPC the handler issued. Returned as an unmodifiable view; callers
   * must not attempt to mutate. Entries are accumulated while the operation handler runs (the call
   * that flows through {@link
   * io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor#startOperation}) and are
   * drained afterward by the task handler when building the StartOperationResponse.
   */
  public @Nonnull List<Link> getBacklinks() {
    return Collections.unmodifiableList(responseBacklinks);
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
