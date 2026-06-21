package io.temporal.common.converter;

import io.temporal.common.Experimental;

/**
 * Exception thrown by {@link StorageDriver} implementations when store or retrieve operations fail.
 *
 * <p>This exception wraps underlying storage errors (e.g., network failures, permission errors)
 * into a common type that the SDK can handle uniformly.
 */
@Experimental
public class StorageDriverException extends RuntimeException {

  public StorageDriverException(String message) {
    super(message);
  }

  public StorageDriverException(String message, Throwable cause) {
    super(message, cause);
  }

  public StorageDriverException(Throwable cause) {
    super(cause);
  }
}
