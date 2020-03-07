/*
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

package com.uber.cadence.internal.replay;

import com.uber.cadence.DecisionTaskFailedCause;
import com.uber.cadence.DecisionTaskFailedEventAttributes;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.SearchAttributes;
import com.uber.cadence.TimerFiredEventAttributes;
import com.uber.cadence.UpsertWorkflowSearchAttributesEventAttributes;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.context.ContextPropagator;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.metrics.ReplayAwareScope;
import com.uber.cadence.internal.worker.LocalActivityWorker;
import com.uber.cadence.internal.worker.SingleWorkerOptions;
import com.uber.cadence.workflow.Functions.Func;
import com.uber.cadence.workflow.Functions.Func1;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.Workflow;
import com.uber.m3.tally.Scope;
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

final class DecisionContextImpl implements DecisionContext, HistoryEventHandler {

  private static final Logger log = LoggerFactory.getLogger(DecisionContextImpl.class);

  private final ActivityDecisionContext activityClient;
  private final WorkflowDecisionContext workflowClient;
  private final ClockDecisionContext workflowClock;
  private final WorkflowContext workflowContext;
  private final Scope metricsScope;
  private final boolean enableLoggingInReplay;

  DecisionContextImpl(
      DecisionsHelper decisionsHelper,
      String domain,
      PollForDecisionTaskResponse decisionTask,
      WorkflowExecutionStartedEventAttributes startedAttributes,
      SingleWorkerOptions options,
      BiFunction<LocalActivityWorker.Task, Duration, Boolean> laTaskPoller,
      ReplayDecider replayDecider) {
    this.activityClient = new ActivityDecisionContext(decisionsHelper);
    this.workflowContext =
        new WorkflowContext(
            domain, decisionTask, startedAttributes, options.getContextPropagators());
    this.workflowClient = new WorkflowDecisionContext(decisionsHelper, workflowContext);
    this.workflowClock =
        new ClockDecisionContext(
            decisionsHelper, laTaskPoller, replayDecider, options.getDataConverter());
    this.enableLoggingInReplay = options.getEnableLoggingInReplay();
    this.metricsScope =
        new ReplayAwareScope(options.getMetricsScope(), this, workflowClock::currentTimeMillis);
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
  public ContinueAsNewWorkflowExecutionParameters getContinueAsNewOnCompletion() {
    return workflowContext.getContinueAsNewOnCompletion();
  }

  @Override
  public void setContinueAsNewOnCompletion(
      ContinueAsNewWorkflowExecutionParameters continueParameters) {
    workflowContext.setContinueAsNewOnCompletion(continueParameters);
  }

  @Override
  public int getExecutionStartToCloseTimeoutSeconds() {
    return workflowContext.getExecutionStartToCloseTimeoutSeconds();
  }

  @Override
  public Duration getDecisionTaskTimeout() {
    return Duration.ofSeconds(workflowContext.getDecisionTaskTimeoutSeconds());
  }

  @Override
  public String getTaskList() {
    return workflowContext.getTaskList();
  }

  @Override
  public String getDomain() {
    return workflowContext.getDomain();
  }

  @Override
  public String getWorkflowId() {
    return workflowContext.getWorkflowExecution().getWorkflowId();
  }

  @Override
  public String getRunId() {
    return workflowContext.getWorkflowExecution().getRunId();
  }

  @Override
  public Duration getExecutionStartToCloseTimeout() {
    return Duration.ofSeconds(workflowContext.getExecutionStartToCloseTimeoutSeconds());
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
      ExecuteActivityParameters parameters, BiConsumer<byte[], Exception> callback) {
    return activityClient.scheduleActivityTask(parameters, callback);
  }

  @Override
  public Consumer<Exception> scheduleLocalActivityTask(
      ExecuteLocalActivityParameters parameters, BiConsumer<byte[], Exception> callback) {
    return workflowClock.scheduleLocalActivityTask(parameters, callback);
  }

  @Override
  public Consumer<Exception> startChildWorkflow(
      StartChildWorkflowExecutionParameters parameters,
      Consumer<WorkflowExecution> executionCallback,
      BiConsumer<byte[], Exception> callback) {
    return workflowClient.startChildWorkflow(parameters, executionCallback, callback);
  }

  @Override
  public boolean isServerSideChildWorkflowRetry() {
    return workflowClient.isChildWorkflowExecutionStartedWithRetryOptions();
  }

  @Override
  public boolean isServerSideActivityRetry() {
    return activityClient.isActivityScheduledWithRetryOptions();
  }

  @Override
  public Consumer<Exception> signalWorkflowExecution(
      SignalExternalWorkflowParameters signalParameters, BiConsumer<Void, Exception> callback) {
    return workflowClient.signalWorkflowExecution(signalParameters, callback);
  }

  @Override
  public Promise<Void> requestCancelWorkflowExecution(WorkflowExecution execution) {
    workflowClient.requestCancelWorkflowExecution(execution);
    // TODO: Make promise return success or failure of the cancellation request.
    return Workflow.newPromise(null);
  }

  @Override
  public void continueAsNewOnCompletion(ContinueAsNewWorkflowExecutionParameters parameters) {
    workflowClient.continueAsNewOnCompletion(parameters);
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
  public byte[] sideEffect(Func<byte[]> func) {
    return workflowClock.sideEffect(func);
  }

  @Override
  public Optional<byte[]> mutableSideEffect(
      String id, DataConverter converter, Func1<Optional<byte[]>, Optional<byte[]>> func) {
    return workflowClock.mutableSideEffect(id, converter, func);
  }

  @Override
  public int getVersion(
      String changeID, DataConverter converter, int minSupported, int maxSupported) {
    return workflowClock.getVersion(changeID, converter, minSupported, maxSupported);
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
  public void handleChildWorkflowExecutionCancelRequested(HistoryEvent event) {}

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

  public void handleDecisionTaskFailed(HistoryEvent event) {
    DecisionTaskFailedEventAttributes attr = event.getDecisionTaskFailedEventAttributes();
    if (attr != null && attr.getCause() == DecisionTaskFailedCause.RESET_WORKFLOW) {
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
