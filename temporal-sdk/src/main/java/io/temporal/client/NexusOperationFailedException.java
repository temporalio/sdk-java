package io.temporal.client;

import javax.annotation.Nullable;

/**
 * Thrown by {@link UntypedNexusOperationHandle#getResult} when the standalone Nexus operation was
 * not successful. The original cause can be retrieved via {@link #getCause()}.
 *
 * <p>Standalone Nexus Operations are in Public Preview. We aim to preserve backward compatibility
 * and avoid breaking changes.
 */
public final class NexusOperationFailedException extends NexusOperationException {

  public NexusOperationFailedException(
      String message, String operationId, @Nullable String runId, @Nullable Throwable cause) {
    super(message, operationId, runId, cause);
  }
}
