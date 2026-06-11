package io.temporal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/**
 * Thrown by {@link NexusClient} / {@link NexusServiceClient} when the server returns an
 * ALREADY_EXISTS error because a Nexus operation with the same ID is already running (or has a
 * completed run that conflicts with the requested {@link
 * StartNexusOperationOptions#getIdReusePolicy()} / {@link
 * StartNexusOperationOptions#getIdConflictPolicy()}).
 */
@Experimental
public final class NexusOperationAlreadyStartedException extends NexusOperationException {

  private final String operation;

  public NexusOperationAlreadyStartedException(
      String operationId, String operation, @Nullable String runId, Throwable cause) {
    super(
        "Nexus operation already started: operationId='"
            + operationId
            + "', operation='"
            + operation
            + (runId != null ? "', runId='" + runId + "'" : "'"),
        operationId,
        runId,
        cause);
    this.operation = operation;
  }

  /** The Nexus operation name that was requested. */
  public String getOperation() {
    return operation;
  }
}
