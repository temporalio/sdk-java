package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.failure.TemporalException;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/** Base exception for all workflow failures. */
public abstract class WorkflowException extends TemporalException {

  private final WorkflowExecution execution;
  private final Optional<String> workflowType;

  protected WorkflowException(
      @Nonnull WorkflowExecution execution, String workflowType, Throwable cause) {
    super(getMessage(execution, workflowType), cause);
    this.execution = Objects.requireNonNull(execution);
    this.workflowType = Optional.ofNullable(workflowType);
  }

  protected WorkflowException(
      String message, WorkflowExecution execution, String workflowType, Throwable cause) {
    super(message, cause);
    this.execution = Objects.requireNonNull(execution);
    this.workflowType = Optional.ofNullable(workflowType);
  }

  public WorkflowExecution getExecution() {
    return execution;
  }

  public Optional<String> getWorkflowType() {
    return workflowType;
  }

  private static String getMessage(WorkflowExecution execution, String workflowType) {
    return "workflowId='"
        + execution.getWorkflowId()
        + "', runId='"
        + execution.getRunId()
        + (workflowType == null ? "'" : "', workflowType='" + workflowType + '\'');
  }
}
