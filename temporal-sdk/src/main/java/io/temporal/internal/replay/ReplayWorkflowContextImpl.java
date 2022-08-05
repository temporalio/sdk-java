/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.replay;

import com.uber.m3.tally.Scope;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.RequestCancelExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.common.context.ContextPropagator;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Functions.Func1;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * TODO callbacks usage is non consistent. It accepts Optional and Exception which can be null.
 * Switch both to nullable.
 */
final class ReplayWorkflowContextImpl implements ReplayWorkflowContext {

  private final WorkflowStateMachines workflowStateMachines;
  private final WorkflowContext workflowContext;
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
    this.workflowContext =
        new WorkflowContext(
            namespace,
            workflowExecution,
            startedAttributes,
            runStartedTimestampMillis,
            workerOptions.getContextPropagators());
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
    return workflowContext.getWorkflowExecution();
  }

  @Override
  public WorkflowExecution getParentWorkflowExecution() {
    return workflowContext.getParentWorkflowExecution();
  }

  @Override
  public Optional<String> getContinuedExecutionRunId() {
    return workflowContext.getContinuedExecutionRunId();
  }

  @Override
  public WorkflowType getWorkflowType() {
    return workflowContext.getWorkflowType();
  }

  @Override
  public boolean isCancelRequested() {
    return workflowContext.isCancelRequested();
  }

  void setCancelRequested(boolean flag) {
    workflowContext.setCancelRequested(flag);
  }

  @Override
  public ContinueAsNewWorkflowExecutionCommandAttributes getContinueAsNewOnCompletion() {
    return workflowContext.getContinueAsNewOnCompletion();
  }

  @Override
  public Duration getWorkflowTaskTimeout() {
    return workflowContext.getWorkflowTaskTimeout();
  }

  @Override
  public String getTaskQueue() {
    return workflowContext.getTaskQueue();
  }

  @Override
  public String getNamespace() {
    return workflowContext.getNamespace();
  }

  @Override
  public String getWorkflowId() {
    return workflowContext.getWorkflowExecution().getWorkflowId();
  }

  @Override
  public String getRunId() {
    String result = workflowContext.getWorkflowExecution().getRunId();
    if (result.isEmpty()) {
      return null;
    }
    return result;
  }

  @Override
  public Duration getWorkflowRunTimeout() {
    return workflowContext.getWorkflowRunTimeout();
  }

  @Override
  public Duration getWorkflowExecutionTimeout() {
    return workflowContext.getWorkflowExecutionTimeout();
  }

  @Override
  public long getRunStartedTimestampMillis() {
    return workflowContext.getRunStartedTimestampMillis();
  }

  @Override
  public Payload getMemo(String key) {
    return workflowContext.getMemo(key);
  }

  @Override
  @Nullable
  public SearchAttributes getSearchAttributes() {
    return workflowContext.getSearchAttributes();
  }

  public List<ContextPropagator> getContextPropagators() {
    return workflowContext.getContextPropagators();
  }

  @Override
  public Map<String, Object> getPropagatedContexts() {
    return workflowContext.getPropagatedContexts();
  }

  @Override
  public Functions.Proc1<Exception> scheduleActivityTask(
      ExecuteActivityParameters parameters, Functions.Proc2<Optional<Payloads>, Failure> callback) {
    ScheduleActivityTaskCommandAttributes.Builder attributes = parameters.getAttributes();
    if (attributes.getActivityId().isEmpty()) {
      attributes.setActivityId(workflowStateMachines.randomUUID().toString());
    }
    Functions.Proc cancellationHandler =
        workflowStateMachines.scheduleActivityTask(parameters, callback);
    return (exception) -> cancellationHandler.apply();
  }

  @Override
  public Functions.Proc scheduleLocalActivityTask(
      ExecuteLocalActivityParameters parameters,
      Functions.Proc2<Optional<Payloads>, Failure> callback) {
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
  public Functions.Proc1<Exception> signalExternalWorkflowExecution(
      SignalExternalWorkflowExecutionCommandAttributes.Builder attributes,
      Functions.Proc2<Void, Failure> callback) {
    Functions.Proc cancellationHandler =
        workflowStateMachines.signalExternalWorkflowExecution(attributes.build(), callback);
    return (e) -> cancellationHandler.apply();
  }

  @Override
  public void requestCancelExternalWorkflowExecution(
      WorkflowExecution execution, Functions.Proc2<Void, RuntimeException> callback) {
    RequestCancelExternalWorkflowExecutionCommandAttributes attributes =
        RequestCancelExternalWorkflowExecutionCommandAttributes.newBuilder()
            .setWorkflowId(execution.getWorkflowId())
            .setRunId(execution.getRunId())
            .build();
    workflowStateMachines.requestCancelExternalWorkflowExecution(attributes, callback);
  }

  @Override
  public void continueAsNewOnCompletion(
      ContinueAsNewWorkflowExecutionCommandAttributes attributes) {
    workflowContext.setContinueAsNewOnCompletion(attributes);
  }

  @Override
  public boolean isReplaying() {
    return workflowStateMachines.isReplaying();
  }

  @Override
  public Functions.Proc1<RuntimeException> newTimer(
      Duration delay, Functions.Proc1<RuntimeException> callback) {
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
        workflowStateMachines.newTimer(attributes, (event) -> handleTimerCallback(callback, event));
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
      Func<Optional<Payloads>> func, Functions.Proc1<Optional<Payloads>> callback) {
    workflowStateMachines.sideEffect(func, callback);
  }

  @Override
  public void mutableSideEffect(
      String id,
      Func1<Optional<Payloads>, Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback) {
    workflowStateMachines.mutableSideEffect(id, func, callback);
  }

  @Override
  public void getVersion(
      String changeId,
      int minSupported,
      int maxSupported,
      Functions.Proc2<Integer, RuntimeException> callback) {
    workflowStateMachines.getVersion(changeId, minSupported, maxSupported, callback);
  }

  @Override
  public long currentTimeMillis() {
    return workflowStateMachines.currentTimeMillis();
  }

  @Override
  public void upsertSearchAttributes(SearchAttributes searchAttributes) {
    workflowStateMachines.upsertSearchAttributes(searchAttributes);
    workflowContext.mergeSearchAttributes(searchAttributes);
  }

  @Override
  public int getAttempt() {
    return workflowContext.getAttempt();
  }

  @Override
  public String getCronSchedule() {
    return workflowContext.getCronSchedule();
  }

  @Nullable
  @Override
  public String getFullReplayDirectQueryName() {
    return fullReplayDirectQueryName;
  }
}
