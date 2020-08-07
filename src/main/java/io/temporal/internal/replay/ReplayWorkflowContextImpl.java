/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.replay;

import static io.temporal.internal.common.OptionsUtils.roundUpToSeconds;

import com.uber.m3.tally.Scope;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.RequestCancelExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskFailedEventAttributes;
import io.temporal.common.context.ContextPropagator;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.metrics.ReplayAwareScope;
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

/**
 * TODO(maxim): callbacks usage is non consistent. It accepts Optional and Exception which can be
 * null. Either switch both to Optional or both to nullable.
 */
final class ReplayWorkflowContextImpl implements ReplayWorkflowContext {

  private final WorkflowContext workflowContext;
  private final Scope metricsScope;
  private final boolean enableLoggingInReplay;
  private final WorkflowStateMachines workflowStateMachines;

  ReplayWorkflowContextImpl(
      WorkflowStateMachines workflowStateMachines,
      String namespace,
      WorkflowExecutionStartedEventAttributes startedAttributes,
      WorkflowExecution workflowExecution,
      long runStartedTimestampMillis,
      SingleWorkerOptions options,
      Scope metricsScope) {
    this.workflowStateMachines = workflowStateMachines;
    this.workflowContext =
        new WorkflowContext(
            namespace,
            workflowExecution,
            startedAttributes,
            runStartedTimestampMillis,
            options.getContextPropagators());
    this.enableLoggingInReplay = options.getEnableLoggingInReplay();
    this.metricsScope =
        new ReplayAwareScope(metricsScope, this, workflowStateMachines::currentTimeMillis);
  }

  @Override
  public boolean getEnableLoggingInReplay() {
    return enableLoggingInReplay;
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
    return metricsScope;
  }

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
  public void setContinueAsNewOnCompletion(
      ContinueAsNewWorkflowExecutionCommandAttributes attributes) {
    workflowContext.setContinueAsNewOnCompletion(attributes);
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
  public long getWorkflowExecutionExpirationTimestampMillis() {
    return workflowContext.getWorkflowExecutionExpirationTimestampMillis();
  }

  @Override
  public SearchAttributes getSearchAttributes() {
    return workflowContext.getSearchAttributes();
  }

  @Override
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
      Functions.Proc1<WorkflowExecution> executionCallback,
      Functions.Proc2<Optional<Payloads>, Exception> callback) {
    Functions.Proc cancellationHandler =
        workflowStateMachines.startChildWorkflow(parameters, executionCallback, callback);
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

  long getReplayCurrentTimeMilliseconds() {
    return workflowStateMachines.currentTimeMillis();
  }

  @Override
  public boolean isReplaying() {
    return workflowStateMachines.isReplaying();
  }

  @Override
  public Functions.Proc1<RuntimeException> newTimer(
      Duration delay, Functions.Proc1<RuntimeException> callback) {
    if (delay == Duration.ZERO) {
      callback.apply(null);
      return (e) -> {};
    }
    int delaySeconds = roundUpToSeconds(delay);
    StartTimerCommandAttributes attributes =
        StartTimerCommandAttributes.newBuilder()
            .setStartToFireTimeout(ProtobufTimeUtils.ToProtoDuration(delay))
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
          CanceledFailure exception = new CanceledFailure("Cancelled by request");
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
      String changeId, int minSupported, int maxSupported, Functions.Proc1<Integer> callback) {
    workflowStateMachines.getVersion(changeId, minSupported, maxSupported, callback);
  }

  @Override
  public long currentTimeMillis() {
    return workflowStateMachines.currentTimeMillis();
  }

  public void handleWorkflowTaskFailed(HistoryEvent event) {
    WorkflowTaskFailedEventAttributes attr = event.getWorkflowTaskFailedEventAttributes();
    if (attr != null
        && attr.getCause() == WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_RESET_WORKFLOW) {
      workflowContext.setCurrentRunId(attr.getNewRunId());
    }
  }

  boolean startUnstartedLaTasks(Duration maxWaitAllowed) {
    //    return workflowClock.startUnstartedLaTasks(maxWaitAllowed);
    throw new UnsupportedOperationException("TODO");
  }

  int numPendingLaTasks() {
    //    return workflowClock.numPendingLaTasks();
    // TODO(maxim): implement
    return 0;
  }

  void awaitTaskCompletion(Duration duration) throws InterruptedException {
    //    workflowClock.awaitTaskCompletion(duration);
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public void upsertSearchAttributes(SearchAttributes searchAttributes) {
    //    workflowClock.upsertSearchAttributes(searchAttributes);
    workflowContext.mergeSearchAttributes(searchAttributes);
  }

  //  @Override
  //  public void handleUpsertSearchAttributes(HistoryEvent event) {
  //    UpsertWorkflowSearchAttributesEventAttributes attr =
  //        event.getUpsertWorkflowSearchAttributesEventAttributes();
  //    if (attr != null) {
  //      SearchAttributes searchAttributes = attr.getSearchAttributes();
  //      workflowContext.mergeSearchAttributes(searchAttributes);
  //    }
  //  }
}
