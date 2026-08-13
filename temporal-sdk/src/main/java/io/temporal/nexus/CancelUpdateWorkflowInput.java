package io.temporal.nexus;

import io.temporal.common.Experimental;
import java.util.Objects;

/**
 * Input to {@link TemporalOperationHandler#cancelUpdateWorkflow} describing the workflow update to
 * cancel.
 */
@Experimental
public final class CancelUpdateWorkflowInput {

  private final String workflowId;
  private final String runId;
  private final String updateId;

  public CancelUpdateWorkflowInput(String workflowId, String runId, String updateId) {
    this.workflowId = Objects.requireNonNull(workflowId);
    this.runId = Objects.requireNonNull(runId);
    this.updateId = Objects.requireNonNull(updateId);
  }

  /** Returns the workflow ID extracted from the operation token. */
  public String getWorkflowId() {
    return workflowId;
  }

  /** Returns the run ID extracted from the operation token, or empty if not present. */
  public String getRunId() {
    return runId;
  }

  /** Returns the update ID extracted from the operation token. */
  public String getUpdateId() {
    return updateId;
  }
}
