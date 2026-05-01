package io.temporal.nexus;

import io.nexusrpc.handler.OperationCancelDetails;
import io.nexusrpc.handler.OperationContext;
import io.temporal.common.Experimental;
import java.util.Objects;

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

  /** Returns the operation token identifying the operation to cancel. */
  public String getOperationToken() {
    return operationCancelDetails.getOperationToken();
  }

  /** Returns the underlying {@link OperationContext} for advanced use cases. */
  public OperationContext getOperationContext() {
    return operationContext;
  }

  /** Returns the underlying {@link OperationCancelDetails} for advanced use cases. */
  public OperationCancelDetails getOperationCancelDetails() {
    return operationCancelDetails;
  }
}
