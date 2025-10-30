package io.temporal.internal.replay;

import static io.temporal.internal.history.VersionMarkerUtils.TEMPORAL_CHANGE_VERSION;

import com.uber.m3.tally.Scope;
import io.temporal.api.command.v1.*;
import io.temporal.api.common.v1.*;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.common.RetryOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.*;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Functions.Func1;
import java.time.Duration;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO callbacks usage is non consistent. It accepts Optional and Exception which can be null.
 * Switch both to nullable.
 */
final class ReplayWorkflowContextImpl implements ReplayWorkflowContext {
  private static final Logger log = LoggerFactory.getLogger(ReplayWorkflowContextImpl.class);
  private final BasicWorkflowContext basicWorkflowContext;
  private final WorkflowStateMachines workflowStateMachines;
  private final WorkflowMutableState mutableState;
  private final @Nullable String fullReplayDirectQueryName;
  private final Scope replayAwareWorkflowMetricsScope;
  private final SingleWorkerOptions workerOptions;

  /**
   * @param fullReplayDirectQueryName query name if an execution is a full replay caused by a direct
   *     query, null otherwise
   */
  ReplayWorkflowContextImpl(
      WorkflowStateMachines workflowStateMachines,
      String namespace,
      WorkflowExecutionStartedEventAttributes startedAttributes,
      WorkflowExecution workflowExecution,
      long runStartedTimestampMillis,
      @Nullable String fullReplayDirectQueryName,
      SingleWorkerOptions workerOptions,
      Scope workflowMetricsScope) {
    this.workflowStateMachines = workflowStateMachines;
    this.basicWorkflowContext =
        new BasicWorkflowContext(
            namespace, workflowExecution, startedAttributes, runStartedTimestampMillis);
    this.mutableState = new WorkflowMutableState(startedAttributes);
    this.fullReplayDirectQueryName = fullReplayDirectQueryName;
    this.replayAwareWorkflowMetricsScope =
        new ReplayAwareScope(workflowMetricsScope, this, workflowStateMachines::currentTimeMillis);
    this.workerOptions = workerOptions;
  }

  @Override
  public boolean getEnableLoggingInReplay() {
    return workerOptions.getEnableLoggingInReplay();
  }

  @Override
  public UUID randomUUID() {
    return workflowStateMachines.randomUUID();
  }

  @Override
  public Random newRandom() {
    return workflowStateMachines.newRandom();
  }

  @Override
  public Scope getMetricsScope() {
    return replayAwareWorkflowMetricsScope;
  }

  @Nonnull
  @Override
  public WorkflowExecution getWorkflowExecution() {
    return basicWorkflowContext.getWorkflowExecution();
  }

  @Override
  public WorkflowExecution getParentWorkflowExecution() {
    return basicWorkflowContext.getParentWorkflowExecution();
  }

  @Override
  public WorkflowExecution getRootWorkflowExecution() {
    return basicWorkflowContext.getRootWorkflowExecution();
  }

  @Override
  public String getFirstExecutionRunId() {
    return basicWorkflowContext.getFirstExecutionRunId();
  }

  @Override
  public Optional<String> getContinuedExecutionRunId() {
    return basicWorkflowContext.getContinuedExecutionRunId();
  }

  @Override
  public String getOriginalExecutionRunId() {
    return basicWorkflowContext.getOriginalExecutionRunId();
  }

  @Override
  public WorkflowType getWorkflowType() {
    return basicWorkflowContext.getWorkflowType();
  }

  @Nonnull
  @Override
  public Duration getWorkflowTaskTimeout() {
    return basicWorkflowContext.getWorkflowTaskTimeout();
  }

  @Override
  public String getTaskQueue() {
    return basicWorkflowContext.getTaskQueue();
  }

  @Nullable
  @Override
  public RetryOptions getRetryOptions() {
    return basicWorkflowContext.getRetryOptions();
  }

  @Override
  public String getNamespace() {
    return basicWorkflowContext.getNamespace();
  }

  @Override
  public String getWorkflowId() {
    return basicWorkflowContext.getWorkflowExecution().getWorkflowId();
  }

  @Nonnull
  @Override
  public String getRunId() {
    String result = basicWorkflowContext.getWorkflowExecution().getRunId();
    if (result.isEmpty()) {
      return null;
    }
    return result;
  }

  @Override
  public Duration getWorkflowRunTimeout() {
    return basicWorkflowContext.getWorkflowRunTimeout();
  }

  @Override
  public Duration getWorkflowExecutionTimeout() {
    return basicWorkflowContext.getWorkflowExecutionTimeout();
  }

  @Override
  public long getRunStartedTimestampMillis() {
    return basicWorkflowContext.getRunStartedTimestampMillis();
  }

  @Override
  public Payload getMemo(String key) {
    return mutableState.getMemo(key);
  }

  @Override
  @Nullable
  public SearchAttributes getSearchAttributes() {
    return mutableState.getSearchAttributes();
  }

  @Override
  public ScheduleActivityTaskOutput scheduleActivityTask(
      ExecuteActivityParameters parameters, Functions.Proc2<Optional<Payloads>, Failure> callback) {
    ScheduleActivityTaskCommandAttributes.Builder attributes = parameters.getAttributes();
    if (attributes.getActivityId().isEmpty()) {
      attributes.setActivityId(workflowStateMachines.randomUUID().toString());
    }
    Functions.Proc cancellationHandler =
        workflowStateMachines.scheduleActivityTask(parameters, callback);
    return new ScheduleActivityTaskOutput(
        attributes.getActivityId(), (exception) -> cancellationHandler.apply());
  }

  @Override
  public Functions.Proc scheduleLocalActivityTask(
      ExecuteLocalActivityParameters parameters, LocalActivityCallback callback) {
    return workflowStateMachines.scheduleLocalActivityTask(parameters, callback);
  }

  @Override
  public Functions.Proc1<Exception> startChildWorkflow(
      StartChildWorkflowExecutionParameters parameters,
      Functions.Proc2<WorkflowExecution, Exception> startCallback,
      Functions.Proc2<Optional<Payloads>, Exception> completionCallback) {
    Functions.Proc cancellationHandler =
        workflowStateMachines.startChildWorkflow(parameters, startCallback, completionCallback);
    return (exception) -> cancellationHandler.apply();
  }

  @Override
  public Functions.Proc1<Exception> startNexusOperation(
      StartNexusOperationParameters parameters,
      Functions.Proc2<Optional<String>, Failure> startedCallback,
      Functions.Proc2<Optional<Payload>, Failure> completionCallback) {
    Functions.Proc cancellationHandler =
        workflowStateMachines.startNexusOperation(parameters, startedCallback, completionCallback);
    return (exception) -> cancellationHandler.apply();
  }

  @Override
  public Functions.Proc1<Exception> signalExternalWorkflowExecution(
      SignalExternalWorkflowExecutionCommandAttributes.Builder attributes,
      Functions.Proc2<Void, Failure> callback) {
    Functions.Proc cancellationHandler =
        workflowStateMachines.signalExternalWorkflowExecution(attributes.build(), callback);
    return (e) -> cancellationHandler.apply();
  }

  @Override
  public void requestCancelExternalWorkflowExecution(
      WorkflowExecution execution,
      @Nullable String reason,
      Functions.Proc2<Void, RuntimeException> callback) {
    RequestCancelExternalWorkflowExecutionCommandAttributes.Builder attributes =
        RequestCancelExternalWorkflowExecutionCommandAttributes.newBuilder()
            .setWorkflowId(execution.getWorkflowId())
            .setRunId(execution.getRunId());
    if (reason != null) {
      attributes.setReason(reason);
    }
    workflowStateMachines.requestCancelExternalWorkflowExecution(attributes.build(), callback);
  }

  @Override
  public boolean isReplaying() {
    return workflowStateMachines.isReplaying();
  }

  @Override
  public boolean tryUseSdkFlag(SdkFlag flag) {
    return workflowStateMachines.tryUseSdkFlag(flag);
  }

  @Override
  public boolean checkSdkFlag(SdkFlag flag) {
    return workflowStateMachines.checkSdkFlag(flag);
  }

  @Override
  public Optional<String> getCurrentBuildId() {
    String curTaskBID = workflowStateMachines.getCurrentTaskBuildId();
    // The current task started id == 0 check is to avoid setting the build id to this worker's ID
    // in the event we're
    // servicing a query, in which case we do want to use the ID from history.
    if (!workflowStateMachines.isReplaying()
        && workflowStateMachines.getCurrentWFTStartedEventId() != 0) {
      curTaskBID = workerOptions.getBuildId();
    }
    return Optional.ofNullable(curTaskBID);
  }

  @Override
  public Priority getPriority() {
    return basicWorkflowContext.getPriority();
  }

  @Override
  public Functions.Proc1<RuntimeException> newTimer(
      Duration delay, UserMetadata metadata, Functions.Proc1<RuntimeException> callback) {
    if (delay.compareTo(Duration.ZERO) <= 0) {
      callback.apply(null);
      return (e) -> {};
    }
    StartTimerCommandAttributes attributes =
        StartTimerCommandAttributes.newBuilder()
            .setStartToFireTimeout(ProtobufTimeUtils.toProtoDuration(delay))
            .setTimerId(workflowStateMachines.randomUUID().toString())
            .build();
    Functions.Proc cancellationHandler =
        workflowStateMachines.newTimer(
            attributes, metadata, (event) -> handleTimerCallback(callback, event));
    return (e) -> cancellationHandler.apply();
  }

  private void handleTimerCallback(Functions.Proc1<RuntimeException> callback, HistoryEvent event) {
    switch (event.getEventType()) {
      case EVENT_TYPE_TIMER_FIRED:
        {
          callback.apply(null);
          return;
        }
      case EVENT_TYPE_TIMER_CANCELED:
        {
          CanceledFailure exception = new CanceledFailure("Canceled by request");
          callback.apply(exception);
          return;
        }
      default:
        throw new IllegalArgumentException("Unexpected event type: " + event.getEventType());
    }
  }

  @Override
  public void sideEffect(
      Func<Optional<Payloads>> func,
      UserMetadata metadata,
      Functions.Proc1<Optional<Payloads>> callback) {
    workflowStateMachines.sideEffect(func, metadata, callback);
  }

  @Override
  public void mutableSideEffect(
      String id,
      UserMetadata metadata,
      Func1<Optional<Payloads>, Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback) {
    workflowStateMachines.mutableSideEffect(id, metadata, func, callback);
  }

  @Override
  public Integer getVersion(
      String changeId,
      int minSupported,
      int maxSupported,
      Functions.Proc2<Integer, RuntimeException> callback) {
    return workflowStateMachines.getVersion(changeId, minSupported, maxSupported, callback);
  }

  @Override
  public long currentTimeMillis() {
    return workflowStateMachines.currentTimeMillis();
  }

  @Override
  public void upsertSearchAttributes(@Nonnull SearchAttributes searchAttributes) {
    /*
     * Temporal Change Version is a reserved field and should ideally not be set by the user.
     * It is set by the SDK when getVersion is called. We know that users have been setting
     * this field in the past, and we want to avoid breaking their workflows.
     * */
    if (searchAttributes.containsIndexedFields(TEMPORAL_CHANGE_VERSION.getName())) {
      // When we enabled upserting of the search attribute by default, we should consider raising a
      // warning here.
      log.debug(
          "{} is a reserved field. This can be set automatically by the SDK by calling `setEnableUpsertVersionSearchAttributes` on your `WorkflowImplementationOptions`",
          TEMPORAL_CHANGE_VERSION.getName());
    }
    workflowStateMachines.upsertSearchAttributes(searchAttributes);
    mutableState.upsertSearchAttributes(searchAttributes);
  }

  @Override
  public void upsertMemo(@Nonnull Memo memo) {
    workflowStateMachines.upsertMemo(memo);
    mutableState.upsertMemo(memo);
  }

  @Override
  public int getAttempt() {
    return basicWorkflowContext.getAttempt();
  }

  @Override
  public String getCronSchedule() {
    return basicWorkflowContext.getCronSchedule();
  }

  @Override
  @Nullable
  public Payloads getLastCompletionResult() {
    return basicWorkflowContext.getLastCompletionResult();
  }

  @Override
  @Nullable
  public Failure getPreviousRunFailure() {
    return basicWorkflowContext.getPreviousRunFailure();
  }

  @Nullable
  @Override
  public String getFullReplayDirectQueryName() {
    return fullReplayDirectQueryName;
  }

  @Override
  public Map<String, Payload> getHeader() {
    return basicWorkflowContext.getHeader();
  }

  @Override
  public long getLastWorkflowTaskStartedEventId() {
    return workflowStateMachines.getLastWFTStartedEventId();
  }

  @Override
  public long getHistorySize() {
    return workflowStateMachines.getHistorySize();
  }

  @Override
  public boolean isContinueAsNewSuggested() {
    return workflowStateMachines.isContinueAsNewSuggested();
  }

  /*
   * MUTABLE STATE OPERATIONS
   */

  @Override
  public boolean isCancelRequested() {
    return mutableState.isCancelRequested();
  }

  @Override
  public void setCancelRequested() {
    mutableState.setCancelRequested();
  }

  public boolean isWorkflowMethodCompleted() {
    return mutableState.isWorkflowMethodCompleted();
  }

  @Override
  public void setWorkflowMethodCompleted() {
    this.mutableState.setWorkflowMethodCompleted();
  }

  @Override
  public ContinueAsNewWorkflowExecutionCommandAttributes getContinueAsNewOnCompletion() {
    return mutableState.getContinueAsNewOnCompletion();
  }

  @Override
  public void continueAsNewOnCompletion(
      ContinueAsNewWorkflowExecutionCommandAttributes attributes) {
    mutableState.continueAsNewOnCompletion(attributes);
  }

  @Override
  public Throwable getWorkflowTaskFailure() {
    return mutableState.getWorkflowTaskFailure();
  }

  @Override
  public void failWorkflowTask(Throwable failure) {
    mutableState.failWorkflowTask(failure);
  }
}
