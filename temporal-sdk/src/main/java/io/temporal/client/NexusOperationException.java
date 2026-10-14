package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.failure.TemporalException;
import javax.annotation.Nullable;

/** Base exception for standalone Nexus operation execution failures. */
@Experimental
public abstract class NexusOperationException extends TemporalException {

  private final String operationId;
  private final @Nullable String runId;

  protected NexusOperationException(
      String message, String operationId, @Nullable String runId, @Nullable Throwable cause) {
    super(message, cause);
    this.operationId = operationId;
    this.runId = runId;
  }

  /** The ID of the Nexus operation execution that caused this exception. */
  public String getOperationId() {
    return operationId;
  }

  /** The run ID of the Nexus operation execution, or {@code null} if not available. */
  @Nullable
  public String getRunId() {
    return runId;
  }
}
