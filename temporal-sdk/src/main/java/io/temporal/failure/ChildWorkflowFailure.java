package io.temporal.failure;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.RetryState;
import java.util.Objects;

/** <b>This exception is expected to be thrown only by the Temporal framework code.</b> */
public final class ChildWorkflowFailure extends TemporalFailure {

  private final long initiatedEventId;
  private final long startedEventId;
  private final String namespace;
  private final RetryState retryState;
  private final WorkflowExecution execution;
  private final String workflowType;

  public ChildWorkflowFailure(
      long initiatedEventId,
      long startedEventId,
      String workflowType,
      WorkflowExecution execution,
      String namespace,
      RetryState retryState,
      Throwable cause) {
    super(
        getMessage(
            execution, workflowType, initiatedEventId, startedEventId, namespace, retryState),
        null,
        cause);
    this.execution = Objects.requireNonNull(execution);
    this.workflowType = Objects.requireNonNull(workflowType);
    this.initiatedEventId = initiatedEventId;
    this.startedEventId = startedEventId;
    this.namespace = namespace;
    this.retryState = retryState;
  }

  public long getInitiatedEventId() {
    return initiatedEventId;
  }

  public long getStartedEventId() {
    return startedEventId;
  }

  public String getNamespace() {
    return namespace;
  }

  public RetryState getRetryState() {
    return retryState;
  }

  public WorkflowExecution getExecution() {
    return execution;
  }

  public String getWorkflowType() {
    return workflowType;
  }

  public static String getMessage(
      WorkflowExecution execution,
      String workflowType,
      long initiatedEventId,
      long startedEventId,
      String namespace,
      RetryState retryState) {
    return "workflowId='"
        + execution.getWorkflowId()
        + '\''
        + ", runId='"
        + execution.getRunId()
        + '\''
        + ", workflowType='"
        + workflowType
        + '\''
        + ", initiatedEventId="
        + initiatedEventId
        + ", startedEventId="
        + startedEventId
        + ", namespace='"
        + namespace
        + '\''
        + ", retryState="
        + retryState;
  }
}
