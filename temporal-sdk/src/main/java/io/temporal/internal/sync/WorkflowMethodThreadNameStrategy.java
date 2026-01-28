package io.temporal.internal.sync;

import io.temporal.api.common.v1.WorkflowExecution;
import javax.annotation.Nonnull;

public interface WorkflowMethodThreadNameStrategy {
  String WORKFLOW_MAIN_THREAD_PREFIX = "workflow-method";

  @Nonnull
  String createThreadName(@Nonnull WorkflowExecution workflowExecution);
}
