package io.temporal.internal.sync;

import io.temporal.workflow.NexusOperationExecution;
import java.util.Optional;

public class NexusOperationExecutionImpl implements NexusOperationExecution {
  private final Optional<String> operationId;

  public NexusOperationExecutionImpl(Optional<String> operationId) {
    this.operationId = operationId;
  }

  @Override
  public Optional<String> getOperationId() {
    return operationId;
  }
}
