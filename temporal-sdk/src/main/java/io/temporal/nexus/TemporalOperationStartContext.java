package io.temporal.nexus;

import io.nexusrpc.handler.OperationContext;
import io.nexusrpc.handler.OperationStartDetails;
import io.temporal.common.Experimental;
import java.util.Objects;

/**
 * Context for a Nexus start operation. Combines the {@link OperationContext} and {@link
 * OperationStartDetails} into a single object passed to {@link
 * TemporalOperationHandler.StartFunction}.
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

  /** Returns the request ID, commonly used as a workflow ID for idempotency. */
  public String getRequestId() {
    return operationStartDetails.getRequestId();
  }

  /** Returns the underlying {@link OperationContext} for advanced use cases. */
  public OperationContext getOperationContext() {
    return operationContext;
  }

  /** Returns the underlying {@link OperationStartDetails} for advanced use cases. */
  public OperationStartDetails getOperationStartDetails() {
    return operationStartDetails;
  }
}
