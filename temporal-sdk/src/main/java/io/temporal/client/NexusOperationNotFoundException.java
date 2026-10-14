package io.temporal.client;

import javax.annotation.Nullable;

/**
 * Thrown when a Nexus operation with the given ID is not known to the Temporal service or is in an
 * incorrect state to perform the requested operation.
 *
 * <p>Examples of possible causes:
 *
 * <ul>
 *   <li>operation ID doesn't exist
 *   <li>operation was purged from the service after reaching its retention limit
 *   <li>attempt to cancel/terminate/delete an operation that is already closed
 * </ul>
 *
 * <p>Standalone Nexus Operations are in Public Preview. We aim to preserve backward compatibility
 * and avoid breaking changes.
 */
public final class NexusOperationNotFoundException extends NexusOperationException {

  public NexusOperationNotFoundException(
      String operationId, @Nullable String runId, @Nullable Throwable cause) {
    super(
        "Nexus operation not found: operationId='"
            + operationId
            + (runId != null ? "', runId='" + runId + "'" : "'"),
        operationId,
        runId,
        cause);
  }
}
