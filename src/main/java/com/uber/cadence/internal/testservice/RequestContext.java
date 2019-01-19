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

package com.uber.cadence.internal.testservice;

import com.uber.cadence.BadRequestError;
import com.uber.cadence.EntityNotExistsError;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.testservice.TestWorkflowStore.ActivityTask;
import com.uber.cadence.internal.testservice.TestWorkflowStore.DecisionTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class RequestContext {

  @FunctionalInterface
  interface CommitCallback {

    void apply(int historySize) throws InternalServiceError, BadRequestError;
  }

  static final class Timer {

    private final long delaySeconds;
    private final Runnable callback;
    private final String taskInfo;

    Timer(long delaySeconds, Runnable callback, String taskInfo) {
      this.delaySeconds = delaySeconds;
      this.callback = callback;
      this.taskInfo = taskInfo;
    }

    long getDelaySeconds() {
      return delaySeconds;
    }

    Runnable getCallback() {
      return callback;
    }

    String getTaskInfo() {
      return taskInfo;
    }
  }

  private final LongSupplier clock;

  private final ExecutionId executionId;

  private final TestWorkflowMutableState workflowMutableState;

  private final long initialEventId;

  private final List<HistoryEvent> events = new ArrayList<>();
  private final List<CommitCallback> commitCallbacks = new ArrayList<>();
  private DecisionTask decisionTask;
  private final List<ActivityTask> activityTasks = new ArrayList<>();
  private final List<Timer> timers = new ArrayList<>();
  private long workflowCompletedAtEventId = -1;
  private boolean needDecision;
  // How many times call SelfAdvancedTimer#lockTimeSkipping.
  // Negative means how many times to call SelfAdvancedTimer#unlockTimeSkipping.
  private int timerLocks;

  /**
   * Creates an instance of the RequestContext
   *
   * @param clock clock used to timestamp events and schedule timers.
   * @param workflowMutableState state of the execution being updated
   * @param initialEventId expected id of the next event added to the history
   */
  RequestContext(
      LongSupplier clock, TestWorkflowMutableState workflowMutableState, long initialEventId) {
    this.clock = Objects.requireNonNull(clock);
    this.workflowMutableState = Objects.requireNonNull(workflowMutableState);
    this.executionId = Objects.requireNonNull(workflowMutableState.getExecutionId());
    this.initialEventId = initialEventId;
  }

  void add(RequestContext ctx) {
    this.activityTasks.addAll(ctx.getActivityTasks());
    this.timers.addAll(ctx.getTimers());
    this.events.addAll(ctx.getEvents());
  }

  void lockTimer() {
    timerLocks++;
  }

  void unlockTimer() {
    timerLocks--;
  }

  int getTimerLocks() {
    return timerLocks;
  }

  void clearTimersAndLocks() {
    timerLocks = 0;
    timers.clear();
  }

  long currentTimeInNanoseconds() {
    return TimeUnit.MILLISECONDS.toNanos(clock.getAsLong());
  }

  /** Returns eventId of the added event; */
  long addEvent(HistoryEvent event) {
    long eventId = initialEventId + events.size();
    if (WorkflowExecutionUtils.isWorkflowExecutionCompletedEvent(event)) {
      workflowCompletedAtEventId = eventId;
    } else {
      if (workflowCompletedAtEventId > 0 && workflowCompletedAtEventId < eventId) {
        throw new IllegalStateException("Event added after the workflow completion event");
      }
    }
    events.add(event);
    return eventId;
  }

  WorkflowExecution getExecution() {
    return executionId.getExecution();
  }

  public TestWorkflowMutableState getWorkflowMutableState() {
    return workflowMutableState;
  }

  String getDomain() {
    return executionId.getDomain();
  }

  public long getInitialEventId() {
    return initialEventId;
  }

  public long getNextEventId() {
    return initialEventId + events.size();
  }

  /**
   * Decision needed, but there is one already running. So initiate another one as soon as it
   * completes.
   */
  void setNeedDecision(boolean needDecision) {
    this.needDecision = needDecision;
  }

  boolean isNeedDecision() {
    return needDecision;
  }

  void setDecisionTask(DecisionTask decisionTask) {
    this.decisionTask = Objects.requireNonNull(decisionTask);
  }

  void addActivityTask(ActivityTask activityTask) {
    this.activityTasks.add(activityTask);
  }

  void addTimer(long delaySeconds, Runnable callback, String name) {
    Timer timer = new Timer(delaySeconds, callback, name);
    this.timers.add(timer);
  }

  public List<Timer> getTimers() {
    return timers;
  }

  List<ActivityTask> getActivityTasks() {
    return activityTasks;
  }

  DecisionTask getDecisionTask() {
    return decisionTask;
  }

  List<HistoryEvent> getEvents() {
    return events;
  }

  void onCommit(CommitCallback callback) {
    commitCallbacks.add(callback);
  }

  /** @return nextEventId */
  long commitChanges(TestWorkflowStore store)
      throws InternalServiceError, EntityNotExistsError, BadRequestError {
    return store.save(this);
  }

  /** Called by {@link TestWorkflowStore#save(RequestContext)} */
  void fireCallbacks(int historySize) throws InternalServiceError, BadRequestError {
    for (CommitCallback callback : commitCallbacks) {
      callback.apply(historySize);
    }
  }

  ExecutionId getExecutionId() {
    return executionId;
  }

  public boolean isEmpty() {
    return events.isEmpty() && activityTasks.isEmpty() && decisionTask == null && timers.isEmpty();
  }
}
