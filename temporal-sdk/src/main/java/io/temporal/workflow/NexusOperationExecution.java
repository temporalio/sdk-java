package io.temporal.workflow;

import java.util.Optional;

/** NexusOperationExecution identifies a specific Nexus operation execution. */
public interface NexusOperationExecution {
  /**
   * @return the Operation ID as set by the Operation's handler. May be empty if the operation
   *     hasn't started yet or completed synchronously.
   */
  Optional<String> getOperationId();
}
