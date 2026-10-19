package io.temporal.workflow;

import io.temporal.client.WorkflowOptions;
import io.temporal.common.Experimental;

/** Options for signaling an external workflow, starting it if necessary. */
@Experimental
public final class SignalWithStartWorkflowOptions {
  private final WorkflowOptions options;

  private SignalWithStartWorkflowOptions(WorkflowOptions options) {
    this.options = options;
  }

  public static Builder newBuilder() {
    return new Builder(null);
  }

  public static Builder newBuilder(SignalWithStartWorkflowOptions options) {
    return new Builder(options);
  }

  public Builder toBuilder() {
    return newBuilder(this);
  }

  public String getWorkflowId() {
    return options.getWorkflowId();
  }

  public String getTaskQueue() {
    return options.getTaskQueue();
  }

  public java.time.Duration getWorkflowExecutionTimeout() {
    return options.getWorkflowExecutionTimeout();
  }

  public java.time.Duration getWorkflowRunTimeout() {
    return options.getWorkflowRunTimeout();
  }

  public java.time.Duration getWorkflowTaskTimeout() {
    return options.getWorkflowTaskTimeout();
  }

  public io.temporal.api.enums.v1.WorkflowIdReusePolicy getWorkflowIdReusePolicy() {
    return options.getWorkflowIdReusePolicy();
  }

  public io.temporal.api.enums.v1.WorkflowIdConflictPolicy getWorkflowIdConflictPolicy() {
    return options.getWorkflowIdConflictPolicy();
  }

  public io.temporal.common.RetryOptions getRetryOptions() {
    return options.getRetryOptions();
  }

  public String getCronSchedule() {
    return options.getCronSchedule();
  }

  public java.util.Map<String, Object> getMemo() {
    return options.getMemo();
  }

  public io.temporal.common.SearchAttributes getTypedSearchAttributes() {
    return options.getTypedSearchAttributes();
  }

  public io.temporal.common.Priority getPriority() {
    return options.getPriority();
  }

  public io.temporal.common.VersioningOverride getVersioningOverride() {
    return options.getVersioningOverride();
  }

  public java.time.Duration getStartDelay() {
    return options.getStartDelay();
  }

  public String getStaticSummary() {
    return options.getStaticSummary();
  }

  public String getStaticDetails() {
    return options.getStaticDetails();
  }

  public String getRequestId() {
    return options.getRequestId();
  }

  public static final class Builder {
    private final WorkflowOptions.Builder options;

    private Builder(SignalWithStartWorkflowOptions value) {
      options =
          value == null ? WorkflowOptions.newBuilder() : WorkflowOptions.newBuilder(value.options);
    }

    /** Sets the workflow start option; see {@link WorkflowOptions.Builder#setWorkflowId}. */
    public Builder setWorkflowId(String value) {
      options.setWorkflowId(value);
      return this;
    }

    /** Sets the workflow start option; see {@link WorkflowOptions.Builder#setTaskQueue}. */
    public Builder setTaskQueue(String value) {
      options.setTaskQueue(value);
      return this;
    }

    /**
     * Sets the workflow start option; see {@link
     * WorkflowOptions.Builder#setWorkflowExecutionTimeout}.
     */
    public Builder setWorkflowExecutionTimeout(java.time.Duration value) {
      options.setWorkflowExecutionTimeout(value);
      return this;
    }

    /**
     * Sets the workflow start option; see {@link WorkflowOptions.Builder#setWorkflowRunTimeout}.
     */
    public Builder setWorkflowRunTimeout(java.time.Duration value) {
      options.setWorkflowRunTimeout(value);
      return this;
    }

    /**
     * Sets the workflow start option; see {@link WorkflowOptions.Builder#setWorkflowTaskTimeout}.
     */
    public Builder setWorkflowTaskTimeout(java.time.Duration value) {
      options.setWorkflowTaskTimeout(value);
      return this;
    }

    /**
     * Sets the workflow start option; see {@link WorkflowOptions.Builder#setWorkflowIdReusePolicy}.
     */
    public Builder setWorkflowIdReusePolicy(io.temporal.api.enums.v1.WorkflowIdReusePolicy value) {
      options.setWorkflowIdReusePolicy(value);
      return this;
    }

    /**
     * Sets the workflow start option; see {@link
     * WorkflowOptions.Builder#setWorkflowIdConflictPolicy}.
     */
    public Builder setWorkflowIdConflictPolicy(
        io.temporal.api.enums.v1.WorkflowIdConflictPolicy value) {
      options.setWorkflowIdConflictPolicy(value);
      return this;
    }

    /** Sets the workflow start option; see {@link WorkflowOptions.Builder#setRetryOptions}. */
    public Builder setRetryOptions(io.temporal.common.RetryOptions value) {
      options.setRetryOptions(value);
      return this;
    }

    /** Sets the workflow start option; see {@link WorkflowOptions.Builder#setCronSchedule}. */
    public Builder setCronSchedule(String value) {
      options.setCronSchedule(value);
      return this;
    }

    /** Sets the workflow start option; see {@link WorkflowOptions.Builder#setMemo}. */
    public Builder setMemo(java.util.Map<String, Object> value) {
      options.setMemo(value);
      return this;
    }

    /**
     * Sets the workflow start option; see {@link WorkflowOptions.Builder#setTypedSearchAttributes}.
     */
    public Builder setTypedSearchAttributes(io.temporal.common.SearchAttributes value) {
      options.setTypedSearchAttributes(value);
      return this;
    }

    /** Sets the workflow start option; see {@link WorkflowOptions.Builder#setPriority}. */
    public Builder setPriority(io.temporal.common.Priority value) {
      options.setPriority(value);
      return this;
    }

    /**
     * Sets the workflow start option; see {@link WorkflowOptions.Builder#setVersioningOverride}.
     */
    public Builder setVersioningOverride(io.temporal.common.VersioningOverride value) {
      options.setVersioningOverride(value);
      return this;
    }

    /** Sets the workflow start option; see {@link WorkflowOptions.Builder#setStartDelay}. */
    public Builder setStartDelay(java.time.Duration value) {
      options.setStartDelay(value);
      return this;
    }

    /** Sets the workflow start option; see {@link WorkflowOptions.Builder#setStaticSummary}. */
    public Builder setStaticSummary(String value) {
      options.setStaticSummary(value);
      return this;
    }

    /** Sets the workflow start option; see {@link WorkflowOptions.Builder#setStaticDetails}. */
    public Builder setStaticDetails(String value) {
      options.setStaticDetails(value);
      return this;
    }

    /** Sets the workflow start option; see {@link WorkflowOptions.Builder#setRequestId}. */
    public Builder setRequestId(String value) {
      options.setRequestId(value);
      return this;
    }

    public SignalWithStartWorkflowOptions build() {
      return new SignalWithStartWorkflowOptions(options.build());
    }
  }
}
