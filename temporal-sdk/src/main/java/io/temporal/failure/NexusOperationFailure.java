package io.temporal.failure;

import io.temporal.common.Experimental;

/**
 * Contains information about a Nexus operation failure. Always contains the original reason for the
 * failure as its cause. For example if a Nexus operation timed out the cause is {@link
 * TimeoutFailure}.
 *
 * <p><b>This exception is only expected to be thrown by the Temporal SDK.</b>
 */
@Experimental
public final class NexusOperationFailure extends TemporalFailure {
  private final long scheduledEventId;
  private final String endpoint;
  private final String service;
  private final String operation;
  private final String operationToken;

  public NexusOperationFailure(
      String message,
      long scheduledEventId,
      String endpoint,
      String service,
      String operation,
      String operationToken,
      Throwable cause) {
    super(
        getMessage(message, scheduledEventId, endpoint, service, operation, operationToken),
        message,
        cause);
    this.scheduledEventId = scheduledEventId;
    this.endpoint = endpoint;
    this.service = service;
    this.operation = operation;
    this.operationToken = operationToken;
  }

  public static String getMessage(
      String originalMessage,
      long scheduledEventId,
      String endpoint,
      String service,
      String operation,
      String operationToken) {
    return "Nexus Operation with operation='"
        + operation
        + "service='"
        + service
        + "' endpoint='"
        + endpoint
        + "' failed: '"
        + originalMessage
        + "'. "
        + "scheduledEventId="
        + scheduledEventId
        + (operationToken == null ? "" : ", operationToken=" + operationToken);
  }

  public long getScheduledEventId() {
    return scheduledEventId;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public String getService() {
    return service;
  }

  public String getOperation() {
    return operation;
  }

  public String getOperationToken() {
    return operationToken;
  }
}
