package io.temporal.nexus;

import io.nexusrpc.handler.OperationCancelDetails;
import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationMethodCancellationListener;
import io.temporal.common.Experimental;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Context for a Nexus cancel operation request, passed to {@link
 * TemporalOperationHandler#cancelWorkflowRun}.
 */
@Experimental
public final class TemporalOperationCancelContext {

  private final OperationContext operationContext;
  private final OperationCancelDetails operationCancelDetails;

  TemporalOperationCancelContext(
      OperationContext operationContext, OperationCancelDetails operationCancelDetails) {
    this.operationContext = Objects.requireNonNull(operationContext);
    this.operationCancelDetails = Objects.requireNonNull(operationCancelDetails);
  }

  /** Returns the service name for this operation. */
  public String getService() {
    return operationContext.getService();
  }

  /** Returns the operation name. */
  public String getOperation() {
    return operationContext.getOperation();
  }

  /** Returns the headers for this cancel request. The returned map is case-insensitive. */
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

  /** Returns the operation token identifying the operation to cancel. */
  public String getOperationToken() {
    return operationCancelDetails.getOperationToken();
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
}
