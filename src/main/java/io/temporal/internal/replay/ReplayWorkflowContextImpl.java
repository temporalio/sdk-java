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

import com.uber.m3.tally.Scope;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.TimerFiredEventAttributes;
import io.temporal.api.history.v1.UpsertWorkflowSearchAttributesEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskFailedEventAttributes;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.metrics.ReplayAwareScope;
import io.temporal.internal.worker.LocalActivityWorker;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Functions.Func1;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ReplayWorkflowContextImpl implements ReplayWorkflowContext, HistoryEventHandler {

  private static final Logger log = LoggerFactory.getLogger(ReplayWorkflowContextImpl.class);

  private final ActivityDecisionContext activityClient;
  private final WorkflowDecisionContext workflowClient;
  private final ClockDecisionContext workflowClock;
  private final WorkflowContext workflowContext;
  private final Scope metricsScope;
  private final boolean enableLoggingInReplay;

  ReplayWorkflowContextImpl(
      CommandHelper commandHelper,
      String namespace,
      WorkflowExecutionStartedEventAttributes startedAttributes,
      long runStartedTimestampMillis,
      SingleWorkerOptions options,
      Scope metricsScope,
      BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller,
      ReplayWorkflowExecutor replayDecider) {
    this.activityClient = new ActivityDecisionContext(commandHelper);
    this.workflowContext =
        new WorkflowContext(
            namespace,
            commandHelper.getTask(),
            startedAttributes,
            runStartedTimestampMillis,
            options.getContextPropagators());
    this.workflowClient = new WorkflowDecisionContext(commandHelper, workflowContext);
    this.workflowClock =
        new ClockDecisionContext(
            commandHelper, laTaskPoller, replayDecider, options.getDataConverter());
    this.enableLoggingInReplay = options.getEnableLoggingInReplay();
    this.metricsScope = new ReplayAwareScope(metricsScope, this, workflowClock::currentTimeMillis);
  }

  @Override
  public boolean getEnableLoggingInReplay() {
    return enableLoggingInReplay;
  }

  @Override
  public UUID randomUUID() {
    return workflowClient.randomUUID();
  }

  @Override
  public Random newRandom() {
    return workflowClient.newRandom();
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
    return Duration.ofSeconds(workflowContext.getWorkflowTaskTimeoutSeconds());
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
    return Duration.ofSeconds(workflowContext.getWorkflowRunTimeoutSeconds());
  }

  @Override
  public Duration getWorkflowExecutionTimeout() {
    return Duration.ofSeconds(workflowContext.getWorkflowExecutionTimeoutSeconds());
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
  public Consumer<Exception> scheduleActivityTask(
      ExecuteActivityParameters parameters, BiConsumer<Optional<Payloads>, Exception> callback) {
    return activityClient.scheduleActivityTask(parameters, callback);
  }

  @Override
  public Consumer<Exception> scheduleLocalActivityTask(
      ExecuteLocalActivityParameters parameters,
      BiConsumer<Optional<Payloads>, Exception> callback) {
    return workflowClock.scheduleLocalActivityTask(parameters, callback);
  }

  @Override
  public Consumer<Exception> startChildWorkflow(
      StartChildWorkflowExecutionParameters parameters,
      Consumer<WorkflowExecution> executionCallback,
      BiConsumer<Optional<Payloads>, Exception> callback) {
    return workflowClient.startChildWorkflow(parameters, executionCallback, callback);
  }

  @Override
  public Consumer<Exception> signalWorkflowExecution(
      SignalExternalWorkflowExecutionCommandAttributes.Builder attributes,
      BiConsumer<Void, Exception> callback) {
    return workflowClient.signalWorkflowExecution(attributes, callback);
  }

  @Override
  public Promise<Void> requestCancelWorkflowExecution(WorkflowExecution execution) {
    workflowClient.requestCancelWorkflowExecution(execution);
    // TODO: Make promise return success or failure of the cancellation request.
    return Workflow.newPromise(null);
  }

  @Override
  public void continueAsNewOnCompletion(
      ContinueAsNewWorkflowExecutionCommandAttributes attributes) {
    workflowClient.continueAsNewOnCompletion(attributes);
  }

  void setReplayCurrentTimeMilliseconds(long replayCurrentTimeMilliseconds) {
    if (replayCurrentTimeMilliseconds < workflowClock.currentTimeMillis()) {
      if (log.isWarnEnabled()) {
        log.warn(
            "Trying to set workflow clock back from "
                + workflowClock.currentTimeMillis()
                + " to "
                + replayCurrentTimeMilliseconds
                + ". This will be a no-op.");
      }
      return;
    }
    workflowClock.setReplayCurrentTimeMilliseconds(replayCurrentTimeMilliseconds);
  }

  long getReplayCurrentTimeMilliseconds() {
    return workflowClock.currentTimeMillis();
  }

  @Override
  public boolean isReplaying() {
    return workflowClock.isReplaying();
  }

  @Override
  public Consumer<Exception> createTimer(long delaySeconds, Consumer<Exception> callback) {
    return workflowClock.createTimer(delaySeconds, callback);
  }

  @Override
  public Optional<Payloads> sideEffect(Func<Optional<Payloads>> func) {
    return workflowClock.sideEffect(func);
  }

  @Override
  public Optional<Payloads> mutableSideEffect(
      String id, DataConverter converter, Func1<Optional<Payloads>, Optional<Payloads>> func) {
    return workflowClock.mutableSideEffect(id, converter, func);
  }

  @Override
  public int getVersion(
      String changeId, DataConverter converter, int minSupported, int maxSupported) {
    return workflowClock.getVersion(changeId, converter, minSupported, maxSupported);
  }

  @Override
  public long currentTimeMillis() {
    return workflowClock.currentTimeMillis();
  }

  void setReplaying(boolean replaying) {
    workflowClock.setReplaying(replaying);
  }

  @Override
  public void handleActivityTaskCanceled(HistoryEvent event) {
    activityClient.handleActivityTaskCanceled(event);
  }

  @Override
  public void handleActivityTaskCompleted(HistoryEvent event) {
    activityClient.handleActivityTaskCompleted(event);
  }

  @Override
  public void handleActivityTaskFailed(HistoryEvent event) {
    activityClient.handleActivityTaskFailed(event);
  }

  @Override
  public void handleActivityTaskTimedOut(HistoryEvent event) {
    activityClient.handleActivityTaskTimedOut(event);
  }

  @Override
  public void handleChildWorkflowExecutionCancelRequested(HistoryEvent event) {
    workflowClient.handleChildWorkflowExecutionCancelRequested(event);
  }

  @Override
  public void handleChildWorkflowExecutionCanceled(HistoryEvent event) {
    workflowClient.handleChildWorkflowExecutionCanceled(event);
  }

  @Override
  public void handleChildWorkflowExecutionStarted(HistoryEvent event) {
    workflowClient.handleChildWorkflowExecutionStarted(event);
  }

  @Override
  public void handleChildWorkflowExecutionTimedOut(HistoryEvent event) {
    workflowClient.handleChildWorkflowExecutionTimedOut(event);
  }

  @Override
  public void handleChildWorkflowExecutionTerminated(HistoryEvent event) {
    workflowClient.handleChildWorkflowExecutionTerminated(event);
  }

  @Override
  public void handleStartChildWorkflowExecutionFailed(HistoryEvent event) {
    workflowClient.handleStartChildWorkflowExecutionFailed(event);
  }

  @Override
  public void handleChildWorkflowExecutionFailed(HistoryEvent event) {
    workflowClient.handleChildWorkflowExecutionFailed(event);
  }

  @Override
  public void handleChildWorkflowExecutionCompleted(HistoryEvent event) {
    workflowClient.handleChildWorkflowExecutionCompleted(event);
  }

  @Override
  public void handleTimerFired(TimerFiredEventAttributes attributes) {
    workflowClock.handleTimerFired(attributes);
  }

  @Override
  public void handleTimerCanceled(HistoryEvent event) {
    workflowClock.handleTimerCanceled(event);
  }

  void handleSignalExternalWorkflowExecutionFailed(HistoryEvent event) {
    workflowClient.handleSignalExternalWorkflowExecutionFailed(event);
  }

  @Override
  public void handleExternalWorkflowExecutionSignaled(HistoryEvent event) {
    workflowClient.handleExternalWorkflowExecutionSignaled(event);
  }

  @Override
  public void handleMarkerRecorded(HistoryEvent event) {
    workflowClock.handleMarkerRecorded(event);
  }

  public void handleWorkflowTaskFailed(HistoryEvent event) {
    WorkflowTaskFailedEventAttributes attr = event.getWorkflowTaskFailedEventAttributes();
    if (attr != null
        && attr.getCause() == WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_RESET_WORKFLOW) {
      workflowContext.setCurrentRunId(attr.getNewRunId());
    }
  }

  boolean startUnstartedLaTasks(Duration maxWaitAllowed) {
    return workflowClock.startUnstartedLaTasks(maxWaitAllowed);
  }

  int numPendingLaTasks() {
    return workflowClock.numPendingLaTasks();
  }

  void awaitTaskCompletion(Duration duration) throws InterruptedException {
    workflowClock.awaitTaskCompletion(duration);
  }

  @Override
  public void upsertSearchAttributes(SearchAttributes searchAttributes) {
    workflowClock.upsertSearchAttributes(searchAttributes);
    workflowContext.mergeSearchAttributes(searchAttributes);
  }

  @Override
  public void handleUpsertSearchAttributes(HistoryEvent event) {
    UpsertWorkflowSearchAttributesEventAttributes attr =
        event.getUpsertWorkflowSearchAttributesEventAttributes();
    if (attr != null) {
      SearchAttributes searchAttributes = attr.getSearchAttributes();
      workflowContext.mergeSearchAttributes(searchAttributes);
    }
  }
}
