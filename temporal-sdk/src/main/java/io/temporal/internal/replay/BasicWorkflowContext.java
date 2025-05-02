package io.temporal.internal.replay;

import static io.temporal.internal.common.RetryOptionsUtils.toRetryOptions;

import com.google.common.base.Preconditions;
import com.google.protobuf.util.Timestamps;
import io.temporal.api.common.v1.*;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.common.RetryOptions;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The most basic context with an information about the Workflow and some mutable state that
 * collects during its execution. This context is not aware about anything else like state machines.
 */
final class BasicWorkflowContext {
  private final long runStartedTimestampMillis;
  private final WorkflowExecutionStartedEventAttributes startedAttributes;
  private final String namespace;
  @Nonnull private final WorkflowExecution workflowExecution;

  @Nullable private final Payloads lastCompletionResult;

  @Nullable private final Failure previousRunFailure;

  BasicWorkflowContext(
      String namespace,
      @Nonnull WorkflowExecution workflowExecution,
      WorkflowExecutionStartedEventAttributes startedAttributes,
      long runStartedTimestampMillis) {
    this.namespace = namespace;
    this.workflowExecution = Preconditions.checkNotNull(workflowExecution);
    this.startedAttributes = startedAttributes;
    this.runStartedTimestampMillis = runStartedTimestampMillis;
    this.lastCompletionResult =
        startedAttributes.hasLastCompletionResult()
            ? startedAttributes.getLastCompletionResult()
            : null;
    this.previousRunFailure =
        startedAttributes.hasContinuedFailure() ? startedAttributes.getContinuedFailure() : null;
  }

  @Nonnull
  WorkflowExecution getWorkflowExecution() {
    return workflowExecution;
  }

  WorkflowType getWorkflowType() {
    return startedAttributes.getWorkflowType();
  }

  @Nonnull
  String getFirstExecutionRunId() {
    return startedAttributes.getFirstExecutionRunId();
  }

  Optional<String> getContinuedExecutionRunId() {
    String runId = startedAttributes.getContinuedExecutionRunId();
    return runId.isEmpty() ? Optional.empty() : Optional.of(runId);
  }

  @Nonnull
  String getOriginalExecutionRunId() {
    return startedAttributes.getOriginalExecutionRunId();
  }

  WorkflowExecution getParentWorkflowExecution() {
    return startedAttributes.hasParentWorkflowExecution()
        ? startedAttributes.getParentWorkflowExecution()
        : null;
  }

  WorkflowExecution getRootWorkflowExecution() {
    return startedAttributes.hasRootWorkflowExecution()
        ? startedAttributes.getRootWorkflowExecution()
        : null;
  }

  Duration getWorkflowRunTimeout() {
    return ProtobufTimeUtils.toJavaDuration(startedAttributes.getWorkflowRunTimeout());
  }

  Duration getWorkflowExecutionTimeout() {
    return ProtobufTimeUtils.toJavaDuration(startedAttributes.getWorkflowExecutionTimeout());
  }

  long getWorkflowExecutionExpirationTimestampMillis() {
    return Timestamps.toMillis(startedAttributes.getWorkflowExecutionExpirationTime());
  }

  long getRunStartedTimestampMillis() {
    return runStartedTimestampMillis;
  }

  Duration getWorkflowTaskTimeout() {
    return ProtobufTimeUtils.toJavaDuration(startedAttributes.getWorkflowTaskTimeout());
  }

  String getTaskQueue() {
    return startedAttributes.getTaskQueue().getName();
  }

  String getNamespace() {
    return namespace;
  }

  public Map<String, Payload> getHeader() {
    return startedAttributes.getHeader().getFieldsMap();
  }

  int getAttempt() {
    return startedAttributes.getAttempt();
  }

  public String getCronSchedule() {
    return startedAttributes.getCronSchedule();
  }

  @Nullable
  public Payloads getLastCompletionResult() {
    return lastCompletionResult;
  }

  @Nullable
  public Failure getPreviousRunFailure() {
    return previousRunFailure;
  }

  @Nullable
  public RetryOptions getRetryOptions() {
    if (!startedAttributes.hasRetryPolicy()) {
      return null;
    }
    return toRetryOptions(startedAttributes.getRetryPolicy());
  }

  @Nonnull
  public Priority getPriority() {
    return startedAttributes.getPriority();
  }
}
