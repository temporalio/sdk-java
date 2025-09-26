package io.temporal.opentelemetry;

import javax.annotation.Nullable;

/**
 * Used when creating an OpenTelemetry span and provides contextual information used for naming and
 * adding attributes to spans.
 */
public class SpanCreationContext {

  private final SpanOperationType spanOperationType;
  private final String actionName;
  private final String workflowId;
  private final String runId;
  private final String parentWorkflowId;
  private final String parentRunId;

  private SpanCreationContext(
      SpanOperationType spanOperationType,
      String actionName,
      String workflowId,
      String runId,
      String parentWorkflowId,
      String parentRunId) {
    this.spanOperationType = spanOperationType;
    this.actionName = actionName;
    this.workflowId = workflowId;
    this.runId = runId;
    this.parentWorkflowId = parentWorkflowId;
    this.parentRunId = parentRunId;
  }

  public SpanOperationType getSpanOperationType() {
    return spanOperationType;
  }

  /**
   * Returns the action name, which is the name of the Workflow or Activity class
   *
   * @return The action name
   */
  public String getActionName() {
    return actionName;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public @Nullable String getRunId() {
    return runId;
  }

  public String getParentWorkflowId() {
    return parentWorkflowId;
  }

  public String getParentRunId() {
    return parentRunId;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private SpanOperationType spanOperationType;
    private String actionName;
    private String workflowId;
    private String runId;
    private String parentWorkflowId;
    private String parentRunId;

    private Builder() {}

    public Builder setSpanOperationType(SpanOperationType spanOperationType) {
      this.spanOperationType = spanOperationType;
      return this;
    }

    public Builder setActionName(String actionName) {
      this.actionName = actionName;
      return this;
    }

    public Builder setWorkflowId(String workflowId) {
      this.workflowId = workflowId;
      return this;
    }

    public Builder setRunId(String runId) {
      this.runId = runId;
      return this;
    }

    public Builder setParentWorkflowId(String parentWorkflowId) {
      this.parentWorkflowId = parentWorkflowId;
      return this;
    }

    public Builder setParentRunId(String parentRunId) {
      this.parentRunId = parentRunId;
      return this;
    }

    public SpanCreationContext build() {
      return new SpanCreationContext(
          spanOperationType, actionName, workflowId, runId, parentWorkflowId, parentRunId);
    }
  }
}
