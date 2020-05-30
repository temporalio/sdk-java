package io.temporal.failure;

import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.failure.ChildWorkflowExecutionFailureInfo;
import io.temporal.proto.failure.Failure;

public final class ChildWorkflowException extends TemporalException {

  private final long initiatedEventId;
  private final long startedEventId;
  private final WorkflowType workflowType;
  private final WorkflowExecution workflowExecution;
  private final String namespace;

  public ChildWorkflowException(Failure failure, Exception cause) {
    super(failure, cause);
    if (!failure.hasChildWorkflowExecutionFailureInfo()) {
      throw new IllegalArgumentException(
          "Activity failure expected: " + failure.getFailureInfoCase());
    }
    ChildWorkflowExecutionFailureInfo info = failure.getChildWorkflowExecutionFailureInfo();
    this.initiatedEventId = info.getInitiatedEventId();
    this.startedEventId = info.getStartedEventId();
    this.workflowType = info.getWorkflowType();
    this.workflowExecution = info.getWorkflowExecution();
    this.namespace = info.getNamespace();
  }

  public long getInitiatedEventId() {
    return initiatedEventId;
  }

  public long getStartedEventId() {
    return startedEventId;
  }

  public WorkflowType getWorkflowType() {
    return workflowType;
  }

  public WorkflowExecution getWorkflowExecution() {
    return workflowExecution;
  }

  public String getNamespace() {
    return namespace;
  }
}
