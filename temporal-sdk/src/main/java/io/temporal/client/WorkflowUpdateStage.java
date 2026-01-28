package io.temporal.client;

import io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage;

public enum WorkflowUpdateStage {
  /**
   * Update request waits for the update to be until the update request has been admitted by the
   * server - it may be the case that due to a considerations like load or resource limits that an
   * update is made to wait before the server will indicate that it has been received and will be
   * processed. This value does not wait for any sort of acknowledgement from a worker.
   */
  ADMITTED(
      UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ADMITTED),

  /**
   * Update request waits for the update to be accepted (and validated, if there is a validator) by
   * the workflow
   */
  ACCEPTED(
      UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED),

  /** Update request waits for the update to be completed. */
  COMPLETED(
      UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_COMPLETED);

  private final UpdateWorkflowExecutionLifecycleStage policy;

  WorkflowUpdateStage(UpdateWorkflowExecutionLifecycleStage policy) {
    this.policy = policy;
  }

  public UpdateWorkflowExecutionLifecycleStage getProto() {
    return policy;
  }
}
