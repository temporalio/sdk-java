package io.temporal.client;

import io.temporal.common.Experimental;
import javax.annotation.Nullable;

/**
 * Thrown by {@link UntypedNexusOperationHandle#getResult} when the standalone Nexus operation was
 * not successful. The original cause can be retrieved via {@link #getCause()}.
 */
@Experimental
public final class NexusOperationFailedException extends NexusOperationException {

  public NexusOperationFailedException(
      String message, String operationId, @Nullable String runId, @Nullable Throwable cause) {
    super(message, operationId, runId, cause);
  }
}
