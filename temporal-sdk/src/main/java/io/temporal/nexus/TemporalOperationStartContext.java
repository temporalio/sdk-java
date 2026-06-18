package io.temporal.nexus;

import io.nexusrpc.Link;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationMethodCancellationListener;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.common.Experimental;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Context for a Nexus start operation request, passed to {@link
 * TemporalOperationHandler.StartHandler} alongside the {@link TemporalNexusClient} and input.
 */
@Experimental
public final class TemporalOperationStartContext {

  private final OperationContext operationContext;
  private final OperationStartDetails operationStartDetails;

  TemporalOperationStartContext(
      OperationContext operationContext, OperationStartDetails operationStartDetails) {
    this.operationContext = Objects.requireNonNull(operationContext);
    this.operationStartDetails = Objects.requireNonNull(operationStartDetails);
  }

  /** Returns the service name for this operation. */
  public String getService() {
    return operationContext.getService();
  }

  /** Returns the operation name. */
  public String getOperation() {
    return operationContext.getOperation();
  }

  /** Returns the headers for this operation request. The returned map is case-insensitive. */
  public Map<String, String> getHeaders() {
    return operationContext.getHeaders();
  }

  /**
   * Returns the deadline for the operation handler method. This is the time by which the method
   * should complete. This is not the operation's deadline.
   */
  public @Nullable Instant getDeadline() {
    return operationContext.getDeadline();
  }

  /** Returns the request ID for this operation. */
  public String getRequestId() {
    return operationStartDetails.getRequestId();
  }

  /**
   * Optional callback URL for asynchronous operations to deliver results to. If present and the
   * implementation is asynchronous, the implementation should ensure this callback is invoked with
   * the result upon completion.
   */
  public @Nullable String getCallbackUrl() {
    return operationStartDetails.getCallbackUrl();
  }

  /** Headers to use on the callback if {@link #getCallbackUrl} is used. */
  public Map<String, String> getCallbackHeaders() {
    return operationStartDetails.getCallbackHeaders();
  }

  /** Links sent by the caller. Handlers may use these as metadata on associated resources. */
  public List<Link> getLinks() {
    return operationStartDetails.getLinks();
  }

  /**
   * True if the handler method has been cancelled. Note, this is method cancellation, unrelated to
   * operation cancellation.
   */
  public boolean isMethodCancelled() {
    return operationContext.isMethodCancelled();
  }

  /**
   * Reason the handler method was cancelled, or null if not cancelled. Note, this is method
   * cancellation, unrelated to operation cancellation.
   */
  public @Nullable String getMethodCancellationReason() {
    return operationContext.getMethodCancellationReason();
  }

  /**
   * Add a listener for method cancellation. The listener is invoked immediately before this method
   * returns if the method is already cancelled. The listener must not block and must not be
   * registered from within another cancellation listener.
   */
  public void addMethodCancellationListener(OperationMethodCancellationListener listener) {
    operationContext.addMethodCancellationListener(listener);
  }

  /**
   * Remove a method cancellation listener, if present. Must not be called from within another
   * cancellation listener.
   */
  public void removeMethodCancellationListener(OperationMethodCancellationListener listener) {
    operationContext.removeMethodCancellationListener(listener);
  }

  /**
   * Associates links with the current operation to be propagated back to the caller. Links are only
   * attached on successful responses.
   */
  public void addResponseLinks(Link... links) {
    operationContext.addLinks(links);
  }
}
