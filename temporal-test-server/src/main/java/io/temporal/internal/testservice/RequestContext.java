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

package io.temporal.internal.testservice;

import com.google.common.base.MoreObjects;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.grpc.Status;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.testservice.TestWorkflowStore.ActivityTask;
import io.temporal.internal.testservice.TestWorkflowStore.WorkflowTask;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class RequestContext {

  @FunctionalInterface
  interface CommitCallback {

    void apply(int historySize);
  }

  static final class Timer {

    private final Duration delay;
    private final Runnable callback;
    private final String taskInfo;
    private Functions.Proc cancellationHandle;
    private final Functions.Proc cancellationHandleWrapper;

    Timer(Duration delay, Runnable callback, String taskInfo) {
      this.delay = delay;
      this.callback = callback;
      this.taskInfo = taskInfo;
      this.cancellationHandleWrapper =
          () -> {
            if (this.cancellationHandle != null) {
              this.cancellationHandle.apply();
            }
          };
    }

    Duration getDelay() {
      return delay;
    }

    Runnable getCallback() {
      return callback;
    }

    String getTaskInfo() {
      return taskInfo;
    }

    public void setCancellationHandle(Functions.Proc cancellationHandle) {
      this.cancellationHandle = cancellationHandle;
    }

    public Functions.Proc getCancellationHandle() {
      return cancellationHandleWrapper;
    }
  }

  static final class TimerLockChange {
    private final String caller;
    /** +1 or -1 */
    private final int change;

    TimerLockChange(String caller, int change) {
      this.caller = Objects.requireNonNull(caller);
      if (change != -1 && change != 1) {
        throw new IllegalArgumentException("Invalid change: " + change);
      }
      this.change = change;
    }

    public String getCaller() {
      return caller;
    }

    public int getChange() {
      return change;
    }
  }

  private final LongSupplier clock;

  private final ExecutionId executionId;

  private final TestWorkflowMutableState workflowMutableState;

  private final long initialEventId;

  private final List<HistoryEvent> events = new ArrayList<>();
  private final List<CommitCallback> commitCallbacks = new ArrayList<>();
  // Contains a workflow task created by the updater that needs to be persisted into a task queue on
  // a commit.
  // If an eager dispatch was performed, it should be reset to null
  private WorkflowTask workflowTaskForMatching;
  private final List<ActivityTask> activityTasks = new ArrayList<>();
  private final List<Timer> timers = new ArrayList<>();
  private long workflowCompletedAtEventId = -1;
  private boolean needWorkflowTask;
  // How many times call SelfAdvancedTimer#lockTimeSkipping.
  // Negative means how many times to call SelfAdvancedTimer#unlockTimeSkipping.
  private final List<TimerLockChange> timerLocks = new ArrayList<>();

  // Contains an exception that may be published by the updater in case if updater needs to perform
  // and commit the changes.
  // The updater can't just throw the exception because it will prevent the changes to be committed.
  // This exception should be thrown at the very end after performing all the commit actions
  private RuntimeException exception;

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
    if (initialEventId <= 0) {
      throw new IllegalArgumentException("Invalid initialEventId: " + initialEventId);
    }
    this.initialEventId = initialEventId;
  }

  void add(RequestContext ctx) {
    this.activityTasks.addAll(ctx.getActivityTasks());
    this.timers.addAll(ctx.getTimers());
    this.events.addAll(ctx.getEvents());
  }

  void lockTimer(String caller) {
    timerLocks.add(new TimerLockChange(caller, +1));
  }

  void unlockTimer(String caller) {
    timerLocks.add(new TimerLockChange(caller, -1));
  }

  List<TimerLockChange> getTimerLocks() {
    return timerLocks;
  }

  void clearTimersAndLocks() {
    timerLocks.clear();
    timers.clear();
  }

  Timestamp currentTime() {
    return Timestamps.fromMillis(clock.getAsLong());
  }

  /** Returns eventId of the added event; */
  long addEvent(HistoryEvent event) {
    if (workflowMutableState.isTerminalState()) {
      throw Status.NOT_FOUND
          .withDescription("workflow execution already completed")
          .asRuntimeException();
    }
    long eventId = initialEventId + events.size();
    if (WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(event)) {
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

  String getNamespace() {
    return executionId.getNamespace();
  }

  public long getInitialEventId() {
    return initialEventId;
  }

  public long getNextEventId() {
    return initialEventId + events.size();
  }

  /**
   * Command needed, but there is one already running. So initiate another one as soon as it
   * completes.
   */
  void setNeedWorkflowTask(boolean needWorkflowTask) {
    this.needWorkflowTask = needWorkflowTask;
  }

  boolean isNeedWorkflowTask() {
    return needWorkflowTask;
  }

  void setWorkflowTaskForMatching(@Nonnull WorkflowTask workflowTaskForMatching) {
    this.workflowTaskForMatching = Objects.requireNonNull(workflowTaskForMatching);
  }

  @Nullable
  WorkflowTask resetWorkflowTaskForMatching() {
    WorkflowTask existingTask = this.workflowTaskForMatching;
    this.workflowTaskForMatching = null;
    return existingTask;
  }

  WorkflowTask getWorkflowTaskForMatching() {
    return workflowTaskForMatching;
  }

  void addActivityTask(ActivityTask activityTask) {
    this.activityTasks.add(activityTask);
  }

  /**
   * @return cancellation handle
   */
  Functions.Proc addTimer(Duration delay, Runnable callback, String name) {
    Timer timer = new Timer(delay, callback, name);
    this.timers.add(timer);
    return timer.getCancellationHandle();
  }

  public List<Timer> getTimers() {
    return timers;
  }

  List<ActivityTask> getActivityTasks() {
    return activityTasks;
  }

  List<HistoryEvent> getEvents() {
    return events;
  }

  void onCommit(CommitCallback callback) {
    commitCallbacks.add(callback);
  }

  /**
   * @return nextEventId
   */
  long commitChanges(TestWorkflowStore store) {
    return store.save(this);
  }

  /** Called by {@link TestWorkflowStore#save(RequestContext)} */
  void fireCallbacks(int historySize) {
    for (CommitCallback callback : commitCallbacks) {
      callback.apply(historySize);
    }
  }

  ExecutionId getExecutionId() {
    return executionId;
  }

  public RuntimeException getException() {
    return exception;
  }

  public void setExceptionIfEmpty(RuntimeException exception) {
    this.exception = MoreObjects.firstNonNull(this.exception, exception);
  }
}
