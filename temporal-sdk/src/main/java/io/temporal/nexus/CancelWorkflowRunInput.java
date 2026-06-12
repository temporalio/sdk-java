package io.temporal.nexus;

import io.temporal.common.Experimental;
import java.util.Objects;

/**
 * Input to {@link TemporalOperationHandler#cancelWorkflowRun} describing the workflow run to
 * cancel.
 */
@Experimental
public final class CancelWorkflowRunInput {

  private final String workflowId;

  public CancelWorkflowRunInput(String workflowId) {
    this.workflowId = Objects.requireNonNull(workflowId);
  }

  /** Returns the workflow ID extracted from the operation token. */
  public String getWorkflowId() {
    return workflowId;
  }
}
