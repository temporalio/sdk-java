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

import com.uber.cadence.EntityNotExistsError;
import com.uber.cadence.EventType;
import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.GetWorkflowExecutionHistoryResponse;
import com.uber.cadence.History;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.HistoryEventFilterType;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.PollForActivityTaskRequest;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.PollForDecisionTaskRequest;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.testservice.RequestContext.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TestWorkflowStoreImpl implements TestWorkflowStore {

  private static final Logger log = LoggerFactory.getLogger(TestWorkflowStoreImpl.class);

  private static class HistoryStore {

    private final Lock lock;
    private final Condition newEventsCondition;
    private final ExecutionId id;
    private final List<HistoryEvent> history = new ArrayList<>();
    private boolean completed;

    private HistoryStore(ExecutionId id, Lock lock) {
      this.id = id;
      this.lock = lock;
      this.newEventsCondition = lock.newCondition();
    }

    private void checkNextEventId(long nextEventId) {
      if (nextEventId != history.size()) {
        throw new IllegalStateException(
            "NextEventId=" + nextEventId + ", historySize=" + history.size() + " for " + id);
      }
    }

    void addAllLocked(List<HistoryEvent> events, long timeInNanos) throws EntityNotExistsError {
      for (HistoryEvent event : events) {
        if (completed) {
          throw new EntityNotExistsError(
              "Attempt to add an event after a completion event: "
                  + WorkflowExecutionUtils.prettyPrintHistoryEvent(event));
        }
        event.setEventId(history.size());
        event.setTimestamp(timeInNanos);
        history.add(event);
        completed = completed || WorkflowExecutionUtils.isWorkflowExecutionCompletedEvent(event);
      }
      newEventsCondition.signal();
    }

    long getNextEventIdLocked() {
      return history.size();
    }

    List<HistoryEvent> getEventsLocked() {
      return history;
    }

    List<HistoryEvent> waitForNewEvents(
        long expectedNextEventId, HistoryEventFilterType filterType) {
      lock.lock();
      try {
        while (true) {
          if (completed || getNextEventIdLocked() > expectedNextEventId) {
            if (filterType == HistoryEventFilterType.CLOSE_EVENT) {
              if (completed) {
                List<HistoryEvent> result = new ArrayList<>(1);
                result.add(history.get(history.size() - 1));
                return result;
              }
              expectedNextEventId = getNextEventIdLocked();
              continue;
            }
            List<HistoryEvent> result =
                new ArrayList<>(((int) (getNextEventIdLocked() - expectedNextEventId)));
            for (int i = (int) expectedNextEventId; i < getNextEventIdLocked(); i++) {
              result.add(history.get(i));
            }
            return result;
          }
          try {
            newEventsCondition.await();
          } catch (InterruptedException e) {
            return null;
          }
        }
      } finally {
        lock.unlock();
      }
    }
  }

  private final Lock lock = new ReentrantLock();

  private final Map<ExecutionId, HistoryStore> histories = new HashMap<>();

  private final Map<TaskListId, BlockingQueue<PollForActivityTaskResponse>> activityTaskLists =
      new HashMap<>();

  private final Map<TaskListId, BlockingQueue<PollForDecisionTaskResponse>> decisionTaskLists =
      new HashMap<>();

  private final SelfAdvancingTimer timerService =
      new SelfAdvancingTimerImpl(System.currentTimeMillis());

  public TestWorkflowStoreImpl() {
    timerService.lockTimeSkipping(); // locked until the first save
  }

  @Override
  public SelfAdvancingTimer getTimer() {
    return timerService;
  }

  @Override
  public long currentTimeMillis() {
    return timerService.getClock().getAsLong();
  }

  @Override
  public long save(RequestContext ctx) throws InternalServiceError, EntityNotExistsError {
    long result;
    lock.lock();
    boolean historiesEmpty = histories.isEmpty();
    try {
      ExecutionId executionId = ctx.getExecutionId();
      HistoryStore history = histories.get(executionId);
      List<HistoryEvent> events = ctx.getEvents();
      if (history == null) {
        if (events.isEmpty()
            || events.get(0).getEventType() != EventType.WorkflowExecutionStarted) {
          throw new IllegalStateException("No history found for " + executionId);
        }
        history = new HistoryStore(executionId, lock);
        histories.put(executionId, history);
      }
      history.checkNextEventId(ctx.getInitialEventId());
      history.addAllLocked(events, ctx.currentTimeInNanoseconds());
      result = history.getNextEventIdLocked();
      timerService.updateLocks(ctx.getTimerLocks());
      ctx.fireCallbacks(history.getEventsLocked().size());
    } finally {
      if (historiesEmpty && !histories.isEmpty()) {
        timerService.unlockTimeSkipping(); // Initially locked in the constructor
      }
      lock.unlock();
    }
    // Push tasks to the queues out of locks
    DecisionTask decisionTask = ctx.getDecisionTask();
    if (decisionTask != null) {
      BlockingQueue<PollForDecisionTaskResponse> decisionsQueue =
          getDecisionTaskListQueue(decisionTask.getTaskListId());
      decisionsQueue.add(decisionTask.getTask());
    }

    List<ActivityTask> activityTasks = ctx.getActivityTasks();
    if (activityTasks != null) {
      for (ActivityTask activityTask : activityTasks) {
        BlockingQueue<PollForActivityTaskResponse> activitiesQueue =
            getActivityTaskListQueue(activityTask.getTaskListId());
        activitiesQueue.add(activityTask.getTask());
      }
    }

    List<Timer> timers = ctx.getTimers();
    if (timers != null) {
      for (Timer t : timers) {
        timerService.schedule(Duration.ofSeconds(t.getDelaySeconds()), t.getCallback());
      }
    }
    return result;
  }

  @Override
  public void registerDelayedCallback(Duration delay, Runnable r) {
    timerService.schedule(delay, r);
  }

  private BlockingQueue<PollForActivityTaskResponse> getActivityTaskListQueue(
      TaskListId taskListId) {
    lock.lock();
    try {
      {
        BlockingQueue<PollForActivityTaskResponse> activitiesQueue =
            activityTaskLists.get(taskListId);
        if (activitiesQueue == null) {
          activitiesQueue = new LinkedBlockingQueue<>();
          activityTaskLists.put(taskListId, activitiesQueue);
        }
        return activitiesQueue;
      }
    } finally {
      lock.unlock();
    }
  }

  private BlockingQueue<PollForDecisionTaskResponse> getDecisionTaskListQueue(
      TaskListId taskListId) {
    lock.lock();
    try {
      BlockingQueue<PollForDecisionTaskResponse> decisionsQueue = decisionTaskLists.get(taskListId);
      if (decisionsQueue == null) {
        decisionsQueue = new LinkedBlockingQueue<>();
        decisionTaskLists.put(taskListId, decisionsQueue);
      }
      return decisionsQueue;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public PollForDecisionTaskResponse pollForDecisionTask(PollForDecisionTaskRequest pollRequest)
      throws InterruptedException {
    TaskListId taskListId =
        new TaskListId(pollRequest.getDomain(), pollRequest.getTaskList().getName());
    BlockingQueue<PollForDecisionTaskResponse> decisionsQueue =
        getDecisionTaskListQueue(taskListId);
    return decisionsQueue.take();
  }

  @Override
  public PollForActivityTaskResponse pollForActivityTask(PollForActivityTaskRequest pollRequest)
      throws InterruptedException {
    TaskListId taskListId =
        new TaskListId(pollRequest.getDomain(), pollRequest.getTaskList().getName());
    BlockingQueue<PollForActivityTaskResponse> activityTaskQueue =
        getActivityTaskListQueue(taskListId);
    return activityTaskQueue.take();
  }

  @Override
  public void sendQueryTask(
      ExecutionId executionId, TaskListId taskList, PollForDecisionTaskResponse task)
      throws EntityNotExistsError {
    lock.lock();
    try {
      HistoryStore history = getHistoryStore(executionId);
      List<HistoryEvent> events = new ArrayList<>(history.getEventsLocked());
      task.setHistory(new History().setEvents(events));
    } finally {
      lock.unlock();
    }
    BlockingQueue<PollForDecisionTaskResponse> decisionsQueue = getDecisionTaskListQueue(taskList);
    decisionsQueue.add(task);
  }

  @Override
  public GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(
      ExecutionId executionId, GetWorkflowExecutionHistoryRequest getRequest)
      throws EntityNotExistsError {
    HistoryStore history;
    // Used to eliminate the race condition on waitForNewEvents
    long expectedNextEventId;
    lock.lock();
    try {
      history = getHistoryStore(executionId);
      if (!getRequest.isWaitForNewEvent()
          && getRequest.getHistoryEventFilterType() != HistoryEventFilterType.CLOSE_EVENT) {
        List<HistoryEvent> events = history.getEventsLocked();
        // Copy the list as it is mutable. Individual events assumed immutable.
        ArrayList<HistoryEvent> eventsCopy = new ArrayList<>(events);
        return new GetWorkflowExecutionHistoryResponse()
            .setHistory(new History().setEvents(eventsCopy));
      }
      expectedNextEventId = history.getNextEventIdLocked();
    } finally {
      lock.unlock();
    }
    List<HistoryEvent> events =
        history.waitForNewEvents(expectedNextEventId, getRequest.getHistoryEventFilterType());
    GetWorkflowExecutionHistoryResponse result = new GetWorkflowExecutionHistoryResponse();
    if (events != null) {
      result.setHistory(new History().setEvents(events));
    }
    return result;
  }

  private HistoryStore getHistoryStore(ExecutionId executionId) throws EntityNotExistsError {
    HistoryStore result = histories.get(executionId);
    if (result == null) {
      WorkflowExecution execution = executionId.getExecution();
      throw new EntityNotExistsError(
          String.format(
              "Workflow execution result not found.  " + "WorkflowId: %s, RunId: %s",
              execution.getWorkflowId(), execution.getRunId()));
    }
    return result;
  }

  @Override
  public void getDiagnostics(StringBuilder result) {
    result.append("Stored Workflows:\n");
    lock.lock();
    try {
      {
        for (Entry<ExecutionId, HistoryStore> entry : this.histories.entrySet()) {
          result.append(entry.getKey());
          result.append("\n");
          result.append(
              WorkflowExecutionUtils.prettyPrintHistory(
                  entry.getValue().getEventsLocked().iterator(), true));
          result.append("\n");
        }
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    timerService.shutdown();
  }
}
