package io.temporal.payload.storage.s3driver;

import io.temporal.common.Experimental;

/** Thrown when an {@link S3StorageDriver} store or retrieve operation fails. */
@Experimental
public class S3StorageException extends RuntimeException {
  public S3StorageException(String message) {
    super(message);
  }

  public S3StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
