package io.temporal.nexus;

import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Input to {@link TemporalOperationHandler#cancelUpdateWorkflow} describing the workflow update to
 * cancel.
 */
@Experimental
public final class CancelUpdateWorkflowExecutionInput {

  private final String workflowId;
  @Nullable private final String runId;
  private final String updateId;

  public CancelUpdateWorkflowExecutionInput(
      String workflowId, @Nullable String runId, String updateId) {
    this.workflowId = Objects.requireNonNull(workflowId);
    this.runId = runId;
    this.updateId = Objects.requireNonNull(updateId);
  }

  /** Returns the workflow ID extracted from the operation token. */
  public String getWorkflowId() {
    return workflowId;
  }

  /** Returns the run ID extracted from the operation token, or null if not present. */
  @Nullable
  public String getRunId() {
    return runId;
  }

  /** Returns the update ID extracted from the operation token. */
  public String getUpdateId() {
    return updateId;
  }
}
