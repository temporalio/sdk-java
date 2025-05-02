package io.temporal.internal.sync;

import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import io.temporal.internal.common.PriorityUtils;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.workflow.WorkflowInfo;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class WorkflowInfoImpl implements WorkflowInfo {

  private final ReplayWorkflowContext context;

  WorkflowInfoImpl(ReplayWorkflowContext context) {
    this.context = context;
  }

  @Override
  public String getNamespace() {
    return context.getNamespace();
  }

  @Override
  public String getWorkflowId() {
    return context.getWorkflowId();
  }

  @Override
  public String getWorkflowType() {
    return context.getWorkflowType().getName();
  }

  @Nonnull
  @Override
  public String getRunId() {
    return context.getRunId();
  }

  @Nonnull
  @Override
  public String getFirstExecutionRunId() {
    return context.getFirstExecutionRunId();
  }

  @Override
  public Optional<String> getContinuedExecutionRunId() {
    return context.getContinuedExecutionRunId();
  }

  @Nonnull
  @Override
  public String getOriginalExecutionRunId() {
    return context.getOriginalExecutionRunId();
  }

  @Override
  public String getTaskQueue() {
    return context.getTaskQueue();
  }

  @Nullable
  @Override
  public RetryOptions getRetryOptions() {
    return context.getRetryOptions();
  }

  @Override
  public Duration getWorkflowRunTimeout() {
    return context.getWorkflowRunTimeout();
  }

  @Override
  public Duration getWorkflowExecutionTimeout() {
    return context.getWorkflowExecutionTimeout();
  }

  @Override
  public long getRunStartedTimestampMillis() {
    return context.getRunStartedTimestampMillis();
  }

  @Override
  @SuppressWarnings("deprecation")
  @Nullable
  public SearchAttributes getSearchAttributes() {
    return context.getSearchAttributes();
  }

  @Override
  public Optional<String> getParentWorkflowId() {
    WorkflowExecution parentWorkflowExecution = context.getParentWorkflowExecution();
    return parentWorkflowExecution == null
        ? Optional.empty()
        : Optional.of(parentWorkflowExecution.getWorkflowId());
  }

  @Override
  public Optional<String> getParentRunId() {
    WorkflowExecution parentWorkflowExecution = context.getParentWorkflowExecution();
    return parentWorkflowExecution == null
        ? Optional.empty()
        : Optional.of(parentWorkflowExecution.getRunId());
  }

  public Optional<String> getRootWorkflowId() {
    WorkflowExecution rootWorkflowExecution = context.getRootWorkflowExecution();
    return rootWorkflowExecution == null
        ? Optional.empty()
        : Optional.of(rootWorkflowExecution.getWorkflowId());
  }

  @Override
  public Optional<String> getRootRunId() {
    WorkflowExecution rootWorkflowExecution = context.getRootWorkflowExecution();
    return rootWorkflowExecution == null
        ? Optional.empty()
        : Optional.of(rootWorkflowExecution.getRunId());
  }

  @Override
  public int getAttempt() {
    return context.getAttempt();
  }

  @Override
  public String getCronSchedule() {
    return context.getCronSchedule();
  }

  @Override
  public long getHistoryLength() {
    return context.getLastWorkflowTaskStartedEventId();
  }

  @Override
  public long getHistorySize() {
    return context.getHistorySize();
  }

  @Override
  public boolean isContinueAsNewSuggested() {
    return context.isContinueAsNewSuggested();
  }

  @Override
  public Optional<String> getCurrentBuildId() {
    return context.getCurrentBuildId();
  }

  @Override
  public Priority getPriority() {
    return PriorityUtils.fromProto(context.getPriority());
  }

  @Override
  public String toString() {
    return "WorkflowInfo{"
        + "namespace="
        + getNamespace()
        + ", workflowId="
        + getWorkflowId()
        + ", runId="
        + getRunId()
        + ", workflowType="
        + getWorkflowType()
        + ", continuedExecutionRunId="
        + getContinuedExecutionRunId()
        + ", taskQueue='"
        + getTaskQueue()
        + '\''
        + ", workflowRunTimeout="
        + getWorkflowRunTimeout()
        + ", workflowExecutionTimeout="
        + getWorkflowExecutionTimeout()
        + ", runStartedTimestampMillis="
        + getRunStartedTimestampMillis()
        + ", searchAttributes="
        + getSearchAttributes()
        + ", parentWorkflowId="
        + getParentWorkflowId()
        + ", parentRunId="
        + getParentRunId()
        + ", rootWorkflowId="
        + getRootWorkflowId()
        + ", rootRunId="
        + getRootRunId()
        + ", attempt="
        + getAttempt()
        + ", cronSchedule="
        + getCronSchedule()
        + ", historyLength="
        + getHistoryLength()
        + '}';
  }
}
