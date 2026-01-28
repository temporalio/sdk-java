package io.temporal.internal.replay;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;

public interface ReplayWorkflowFactory {
  ReplayWorkflow getWorkflow(WorkflowType workflowType, WorkflowExecution workflowExecution)
      throws Exception;

  boolean isAnyTypeSupported();
}
