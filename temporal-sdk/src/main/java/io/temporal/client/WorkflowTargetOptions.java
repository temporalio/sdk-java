package io.temporal.client;

import io.temporal.api.common.v1.WorkflowExecution;
import java.util.Objects;

/** Options for targeting a specific workflow execution. */
public final class WorkflowTargetOptions {
  public static WorkflowTargetOptions.Builder newBuilder() {
    return new WorkflowTargetOptions.Builder();
  }

  public static WorkflowTargetOptions.Builder newBuilder(WorkflowTargetOptions options) {
    return new WorkflowTargetOptions.Builder(options);
  }

  public static WorkflowTargetOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final WorkflowTargetOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = WorkflowTargetOptions.newBuilder().build();
  }

  private final String workflowId;
  private final String runId;
  private final String firstExecutionRunId;

  private WorkflowTargetOptions(String workflowId, String runId, String firstExecutionRunId) {
    this.workflowId = workflowId;
    this.runId = runId;
    this.firstExecutionRunId = firstExecutionRunId;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public String getRunId() {
    return runId;
  }

  public String getFirstExecutionRunId() {
    return firstExecutionRunId;
  }

  public static final class Builder {
    private String workflowId;
    private String runId;
    private String firstExecutionRunId;

    private Builder() {}

    private Builder(WorkflowTargetOptions options) {
      this.workflowId = options.workflowId;
      this.runId = options.runId;
      this.firstExecutionRunId = options.firstExecutionRunId;
    }

    /** Sets the workflowId of the target workflow. */
    public Builder setWorkflowId(String workflowId) {
      this.workflowId = workflowId;
      return this;
    }

    /** Sets the runId of a specific execution of a workflow. */
    public Builder setRunId(String runId) {
      this.runId = runId;
      return this;
    }

    /**
     * Sets the runId of the first execution of a workflow. This is useful for targeting workflows
     * that have been continued as new.
     */
    public Builder setFirstExecutionRunId(String firstExecutionRunId) {
      this.firstExecutionRunId = firstExecutionRunId;
      return this;
    }

    /** Sets both workflowId and runId from a WorkflowExecution object. */
    public Builder setWorkflowExecution(WorkflowExecution execution) {
      this.workflowId = execution.getWorkflowId();
      this.runId = execution.getRunId();
      return this;
    }

    public WorkflowTargetOptions build() {
      return new WorkflowTargetOptions(workflowId, runId, firstExecutionRunId);
    }
  }

  @Override
  public String toString() {
    return "WorkflowTargetOptions{"
        + "workflowId='"
        + workflowId
        + '\''
        + ", runId='"
        + runId
        + '\''
        + ", firstExecutionRunId='"
        + firstExecutionRunId
        + '\''
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowTargetOptions that = (WorkflowTargetOptions) o;
    return Objects.equals(workflowId, that.workflowId)
        && Objects.equals(runId, that.runId)
        && Objects.equals(firstExecutionRunId, that.firstExecutionRunId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(workflowId, runId, firstExecutionRunId);
  }
}
