package io.temporal.internal.nexus;

import com.google.common.base.Strings;
import com.uber.m3.tally.Scope;
import io.nexusrpc.handler.HandlerException;
import io.temporal.api.common.v1.Link;
import io.temporal.client.WorkflowClient;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;
import io.temporal.nexus.NexusOperationContext;
import io.temporal.nexus.NexusOperationInfo;
import io.temporal.payload.context.NexusSerializationContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class InternalNexusOperationContext {
  private final String namespace;
  private final String taskQueue;
  private final String endpoint;
  private final Scope metricScope;
  private final WorkflowClient client;
  NexusOperationOutboundCallsInterceptor outboundCalls;
  // Link returned by the StartWorkflowExecution response when the operation is backed by a workflow
  // (workflow-run operations). Read by NexusStartWorkflowHelper to attach the forward
  // operation->workflow link, fabricating a WORKFLOW_EXECUTION_STARTED link when the server omits
  // one. Distinct from the response links below.
  Link startWorkflowResponseLink;
  // Links extracted from the inbound Nexus task. Stored once at the task-handler boundary so the
  // workflow client can attach them to the outgoing requests it issues (e.g. signal,
  // signalWithStart) via the request's links field.
  private List<Link> requestLinks = Collections.emptyList();
  // The inbound Nexus task's request ID, captured at the task-handler boundary and available to
  // clients executing on the operation-handler thread. RootActivityClientInvoker reuses it for
  // redelivery-safe activity-start deduplication. It is deliberately independent of
  // nexusOperationMetadata, which is scoped to the single backing start because it carries
  // completion-callback semantics.
  private String requestId;
  // Links returned by outbound RPCs the operation handler issues (such as
  // SignalWorkflowExecutionResponse.link or SignalWithStartWorkflowExecutionResponse.signal_link).
  // One entry per outbound RPC that returned a link. Drained
  // by the task handler when building StartOperationResponse so each RPC the handler issued gets a
  // corresponding link on the caller workflow's history event.
  //
  // A handler may issue RPCs from multiple threads, so every read and write of this list is guarded
  // by responseLinksLock and getResponseLinks() returns a defensive copy taken under the lock.
  private final Object responseLinksLock = new Object();
  private final List<Link> responseLinks = new ArrayList<>();

  private NexusOperationMetadata nexusOperationMetadata;
  // Serialization context for the operation this task is for. Set by the task handler once the
  // service and operation names are known, which is only after the request variant has been
  // inspected, so it is null while the task is being dispatched.
  private NexusSerializationContext serializationContext;
  // Set while a TemporalOperationHandler start runs. Signals and signalWithStarts issued then reuse
  // requestId so a retried Nexus task dedupes against the first attempt. Because they share the ID,
  // a second signal could be dropped by the server as a duplicate, so signalClaimed limits the
  // invocation to one.
  private boolean signalDeduplication;
  private final AtomicBoolean signalClaimed = new AtomicBoolean();

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

  /** Sets metadata for the Temporal primitive backing the current Nexus operation. */
  public void setNexusOperationMetadata(NexusOperationMetadata metadata) {
    this.nexusOperationMetadata = metadata;
  }

  public NexusOperationMetadata getNexusOperationMetadata() {
    return nexusOperationMetadata;
  }

  /**
   * Sets the serialization context describing the operation this task is for. Called by the task
   * handler once the request variant has been inspected and the service and operation are known.
   */
  public void setSerializationContext(NexusSerializationContext serializationContext) {
    this.serializationContext = serializationContext;
  }

  /**
   * Serialization context for the operation this task is for, or {@code null} if the service and
   * operation are not known yet.
   */
  public @Nullable NexusSerializationContext getSerializationContext() {
    return serializationContext;
  }

  /**
   * Set the {@code common.v1.Link}s extracted from the inbound Nexus task so they can be attached
   * to RPCs issued by the operation handler.
   */
  public void setRequestLinks(List<Link> links) {
    this.requestLinks = links == null ? Collections.emptyList() : links;
  }

  /** Links from the inbound Nexus task; empty if none. */
  public @Nonnull List<Link> getRequestLinks() {
    return Collections.unmodifiableList(requestLinks);
  }

  /** Set the request ID of the inbound Nexus task, ambient for the whole invocation. */
  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  /** The inbound Nexus task's request ID; {@code null} if not set. */
  public String getRequestId() {
    return requestId;
  }

  /** Makes signals reuse {@link #getRequestId} and limits them to one; see {@link #claimSignal}. */
  public void enableSignalDeduplication() {
    this.signalDeduplication = true;
  }

  /** The request ID signals must reuse; {@code null} outside a Temporal operation start. */
  public @Nullable String getSignalRequestId() {
    return signalDeduplication && !Strings.isNullOrEmpty(requestId) ? requestId : null;
  }

  /**
   * Claims the invocation's single signal. No-op unless {@link #enableSignalDeduplication} was
   * called. Callers must {@link #releaseSignal} if the signal fails, so the handler can send it
   * again.
   */
  public void claimSignal() {
    if (signalDeduplication && !signalClaimed.compareAndSet(false, true)) {
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST,
          new IllegalStateException("Only one signal can be sent per Nexus operation invocation."));
    }
  }

  /**
   * Like {@link #claimSignal}, but also rejects an empty workflow ID: it would be defaulted to a
   * fresh UUID on each retried Nexus task, so the server could not dedupe and would start another
   * workflow.
   */
  public void claimSignalWithStart(@Nullable String workflowId) {
    if (signalDeduplication && Strings.isNullOrEmpty(workflowId)) {
      throw new HandlerException(
          HandlerException.ErrorType.BAD_REQUEST,
          new IllegalArgumentException(
              "signalWithStart from a Nexus operation handler requires a workflow ID."));
    }
    claimSignal();
  }

  /** Frees the signal claimed by {@link #claimSignal} after the signal failed. */
  public void releaseSignal() {
    signalClaimed.set(false);
  }

  public void setStartWorkflowResponseLink(Link link) {
    this.startWorkflowResponseLink = link;
  }

  public Link getStartWorkflowResponseLink() {
    return startWorkflowResponseLink;
  }

  /**
   * Append a response link returned by an outbound RPC the operation handler issued (e.g. signal,
   * signalWithStart, etc). The task handler drains the list when building the operation's
   * StartOperationResponse.
   */
  public void addResponseLink(Link link) {
    if (link != null) {
      synchronized (responseLinksLock) {
        responseLinks.add(link);
      }
    }
  }

  /**
   * Response links from every outbound RPC the handler issued. Returned as an unmodifiable view;
   * callers must not attempt to mutate. Entries are accumulated while the operation handler runs
   * (the call that flows through {@link
   * io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor#startOperation}) and are
   * drained afterward by the task handler when building the StartOperationResponse.
   */
  public @Nonnull List<Link> getResponseLinks() {
    synchronized (responseLinksLock) {
      return Collections.unmodifiableList(new ArrayList<>(responseLinks));
    }
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
