package io.temporal.workflowstreams;

import io.temporal.common.Experimental;

/**
 * Thrown when a flush retry exceeds the client's max retry duration. The pending batch is dropped;
 * if the signal had already been delivered the items are in the log, otherwise they are lost.
 */
@Experimental
public final class FlushTimeoutException extends RuntimeException {
  public FlushTimeoutException(String message) {
    super(message);
  }
}
