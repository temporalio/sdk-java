package io.temporal.internal.sync;

import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.WorkflowExecution;
import javax.annotation.Nonnull;

final class ExecutionInfoStrategy implements WorkflowMethodThreadNameStrategy {
  public static final io.temporal.internal.sync.ExecutionInfoStrategy INSTANCE =
      new io.temporal.internal.sync.ExecutionInfoStrategy();
  private static final int WORKFLOW_ID_TRIM_LENGTH = 50;
  private static final String TRIM_MARKER = "...";

  private ExecutionInfoStrategy() {}

  @Nonnull
  @Override
  public String createThreadName(@Nonnull WorkflowExecution workflowExecution) {
    Preconditions.checkNotNull(workflowExecution, "workflowExecution");
    String workflowId = workflowExecution.getWorkflowId();

    String trimmedWorkflowId =
        workflowId.length() > WORKFLOW_ID_TRIM_LENGTH
            ?
            // add a ' at the end to explicitly show that the id was trimmed
            workflowId.substring(0, WORKFLOW_ID_TRIM_LENGTH) + TRIM_MARKER
            : workflowId;

    return WORKFLOW_MAIN_THREAD_PREFIX
        + "-"
        + trimmedWorkflowId
        + "-"
        + workflowExecution.getRunId();
  }
}
