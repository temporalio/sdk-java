package io.temporal.internal.sync;

import io.temporal.workflow.NexusOperationExecution;
import java.util.Optional;

public class NexusOperationExecutionImpl implements NexusOperationExecution {

  private final Optional<String> operationToken;

  public NexusOperationExecutionImpl(Optional<String> operationToken) {
    this.operationToken = operationToken;
  }

  @Override
  public Optional<String> getOperationToken() {
    return operationToken;
  }
}
