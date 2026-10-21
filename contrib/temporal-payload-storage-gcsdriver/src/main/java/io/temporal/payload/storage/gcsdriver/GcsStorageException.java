package io.temporal.payload.storage.gcsdriver;

import io.temporal.common.Experimental;

/** Thrown when a {@link GcsStorageDriver} store or retrieve operation fails. */
@Experimental
public class GcsStorageException extends RuntimeException {
  public GcsStorageException(String message) {
    super(message);
  }

  public GcsStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
