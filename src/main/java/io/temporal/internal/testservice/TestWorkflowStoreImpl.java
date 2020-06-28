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

package io.temporal.internal.testservice;

import com.google.protobuf.Int64Value;
import io.grpc.Deadline;
import io.grpc.Status;
import io.temporal.common.v1.WorkflowExecution;
import io.temporal.enums.v1.EventType;
import io.temporal.enums.v1.HistoryEventFilterType;
import io.temporal.history.v1.History;
import io.temporal.history.v1.HistoryEvent;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.testservice.RequestContext.Timer;
import io.temporal.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.workflow.v1.WorkflowExecutionInfo;
import io.temporal.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.workflowservice.v1.PollForActivityTaskRequest;
import io.temporal.workflowservice.v1.PollForActivityTaskResponse;
import io.temporal.workflowservice.v1.PollForDecisionTaskRequest;
import io.temporal.workflowservice.v1.PollForDecisionTaskResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TestWorkflowStoreImpl implements TestWorkflowStore {

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

    public boolean isCompleted() {
      return completed;
    }

    public List<HistoryEvent> getHistory() {
      return history;
    }

    private void checkNextEventId(long nextEventId) {
      if (nextEventId != history.size() + 1L && (nextEventId != 0 && history.size() != 0)) {
        throw new IllegalStateException(
            "NextEventId=" + nextEventId + ", historySize=" + history.size() + " for " + id);
      }
    }

    void addAllLocked(List<HistoryEvent> events, long timeInNanos) {
      for (HistoryEvent event : events) {
        HistoryEvent.Builder eBuilder = event.toBuilder();
        if (completed) {
          throw Status.FAILED_PRECONDITION
              .withDescription(
                  "Attempt to add an eBuilder after a completion eBuilder: "
                      + WorkflowExecutionUtils.prettyPrintObject(eBuilder))
              .asRuntimeException();
        }
        eBuilder.setEventId(history.size() + 1L);
        // It can be set in StateMachines.startActivityTask
        if (eBuilder.getTimestamp() == 0) {
          eBuilder.setTimestamp(timeInNanos);
        }
        history.add(eBuilder.build());
        completed = completed || WorkflowExecutionUtils.isWorkflowExecutionCompletedEvent(eBuilder);
      }
      newEventsCondition.signal();
    }

    long getNextEventIdLocked() {
      return history.size() + 1L;
    }

    List<HistoryEvent> getEventsLocked() {
      return history;
    }

    List<HistoryEvent> waitForNewEvents(
        long expectedNextEventId, HistoryEventFilterType filterType, Deadline deadline) {
      long start = System.currentTimeMillis();
      lock.lock();
      try {
        while (true) {
          if (completed || getNextEventIdLocked() > expectedNextEventId) {
            if (filterType == HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT) {
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
            long toWait;
            if (deadline != null) {
              toWait =
                  deadline.timeRemaining(TimeUnit.MILLISECONDS)
                      - System.currentTimeMillis()
                      + start;
              if (toWait <= 0) {
                return null;
              }
              newEventsCondition.await(toWait, TimeUnit.MILLISECONDS);
            } else {
              newEventsCondition.await();
            }
          } catch (InterruptedException e) {
            return null;
          }
        }
      } finally {
        lock.unlock();
      }
    }
  }

  private static final Logger log = LoggerFactory.getLogger(TestWorkflowStoreImpl.class);

  private final Lock lock = new ReentrantLock();

  private final Map<ExecutionId, HistoryStore> histories = new HashMap<>();

  private final Map<TaskQueueId, BlockingQueue<PollForActivityTaskResponse.Builder>>
      activityTaskQueues = new HashMap<>();

  private final Map<TaskQueueId, BlockingQueue<PollForDecisionTaskResponse.Builder>>
      decisionTaskQueues = new HashMap<>();

  private final SelfAdvancingTimer timerService =
      new SelfAdvancingTimerImpl(System.currentTimeMillis());

  public TestWorkflowStoreImpl() {
    // locked until the first save
    timerService.lockTimeSkipping("TestWorkflowStoreImpl constructor");
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
  public long save(RequestContext ctx) {
    long result;
    lock.lock();
    boolean historiesEmpty = histories.isEmpty();
    try {
      ExecutionId executionId = ctx.getExecutionId();
      HistoryStore history = histories.get(executionId);
      List<HistoryEvent> events = ctx.getEvents();
      if (history == null) {
        if (events.isEmpty()
            || events.get(0).getEventType() != EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED) {
          throw new IllegalStateException("No history found for " + executionId);
        }
        history = new HistoryStore(executionId, lock);
        histories.put(executionId, history);
      }
      history.checkNextEventId(ctx.getInitialEventId());
      history.addAllLocked(events, ctx.currentTimeInNanoseconds());
      result = history.getNextEventIdLocked();
      timerService.updateLocks(ctx.getTimerLocks(), "TestWorkflowStoreImpl save");
      ctx.fireCallbacks(history.getEventsLocked().size());
    } finally {
      if (historiesEmpty && !histories.isEmpty()) {
        timerService.unlockTimeSkipping(
            "TestWorkflowStoreImpl save"); // Initially locked in the constructor
      }
      lock.unlock();
    }
    // Push tasks to the queues out of locks
    DecisionTask decisionTask = ctx.getDecisionTask();

    if (decisionTask != null) {
      StickyExecutionAttributes attributes =
          ctx.getWorkflowMutableState().getStickyExecutionAttributes();
      TaskQueueId id =
          new TaskQueueId(
              decisionTask.getTaskQueueId().getNamespace(),
              attributes == null
                  ? decisionTask.getTaskQueueId().getTaskQueueName()
                  : attributes.getWorkerTaskQueue().getName());
      if (id.getTaskQueueName().isEmpty() || id.getNamespace().isEmpty()) {
        throw Status.INTERNAL.withDescription("Invalid TaskQueueId: " + id).asRuntimeException();
      }
      BlockingQueue<PollForDecisionTaskResponse.Builder> decisionsQueue =
          getDecisionTaskQueueQueue(id);
      decisionsQueue.add(decisionTask.getTask());
    }

    List<ActivityTask> activityTasks = ctx.getActivityTasks();
    if (activityTasks != null) {
      for (ActivityTask activityTask : activityTasks) {
        BlockingQueue<PollForActivityTaskResponse.Builder> activitiesQueue =
            getActivityTaskQueueQueue(activityTask.getTaskQueueId());
        activitiesQueue.add(activityTask.getTask());
      }
    }

    List<Timer> timers = ctx.getTimers();
    if (timers != null) {
      for (Timer t : timers) {
        log.trace(
            "scheduling timer with "
                + t.getDelaySeconds()
                + "seconds delay. Current time="
                + this.currentTimeMillis());
        timerService.schedule(
            Duration.ofSeconds(t.getDelaySeconds()), t.getCallback(), t.getTaskInfo());
      }
    }
    return result;
  }

  @Override
  public void applyTimersAndLocks(RequestContext ctx) {
    lock.lock();
    try {
      timerService.updateLocks(ctx.getTimerLocks(), "TestWorkflowStoreImpl applyTimersAndLocks");
    } finally {
      lock.unlock();
    }

    List<Timer> timers = ctx.getTimers();
    if (timers != null) {
      for (Timer t : timers) {
        timerService.schedule(
            Duration.ofSeconds(t.getDelaySeconds()), t.getCallback(), t.getTaskInfo());
      }
    }

    ctx.clearTimersAndLocks();
  }

  @Override
  public void registerDelayedCallback(Duration delay, Runnable r) {
    timerService.schedule(delay, r, "registerDelayedCallback");
  }

  private BlockingQueue<PollForActivityTaskResponse.Builder> getActivityTaskQueueQueue(
      TaskQueueId taskQueueId) {
    lock.lock();
    try {
      {
        BlockingQueue<PollForActivityTaskResponse.Builder> activitiesQueue =
            activityTaskQueues.get(taskQueueId);
        if (activitiesQueue == null) {
          activitiesQueue = new LinkedBlockingQueue<>();
          activityTaskQueues.put(taskQueueId, activitiesQueue);
        }
        return activitiesQueue;
      }
    } finally {
      lock.unlock();
    }
  }

  private BlockingQueue<PollForDecisionTaskResponse.Builder> getDecisionTaskQueueQueue(
      TaskQueueId taskQueueId) {
    lock.lock();
    try {
      BlockingQueue<PollForDecisionTaskResponse.Builder> decisionsQueue =
          decisionTaskQueues.get(taskQueueId);
      if (decisionsQueue == null) {
        decisionsQueue = new LinkedBlockingQueue<>();
        decisionTaskQueues.put(taskQueueId, decisionsQueue);
      }
      return decisionsQueue;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Optional<PollForDecisionTaskResponse.Builder> pollForDecisionTask(
      PollForDecisionTaskRequest pollRequest, Deadline deadline) {
    TaskQueueId taskQueueId =
        new TaskQueueId(pollRequest.getNamespace(), pollRequest.getTaskQueue().getName());
    BlockingQueue<PollForDecisionTaskResponse.Builder> decisionsQueue =
        getDecisionTaskQueueQueue(taskQueueId);
    if (log.isTraceEnabled()) {
      log.trace(
          "Poll request on decision task queue about to block waiting for a task on "
              + taskQueueId);
    }
    PollForDecisionTaskResponse.Builder result = null;
    try {
      if (deadline == null) {
        result = decisionsQueue.take();
      } else {
        result =
            decisionsQueue.poll(
                deadline.timeRemaining(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException e) {
      // Intentionally left empty
    }
    return Optional.ofNullable(result);
  }

  @Override
  public Optional<PollForActivityTaskResponse.Builder> pollForActivityTask(
      PollForActivityTaskRequest pollRequest, Deadline deadline) {
    TaskQueueId taskQueueId =
        new TaskQueueId(pollRequest.getNamespace(), pollRequest.getTaskQueue().getName());
    BlockingQueue<PollForActivityTaskResponse.Builder> activityTaskQueue =
        getActivityTaskQueueQueue(taskQueueId);
    PollForActivityTaskResponse.Builder result = null;
    try {
      if (deadline == null) {
        result = activityTaskQueue.take();
      } else {
        result =
            activityTaskQueue.poll(
                deadline.timeRemaining(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException e) {
      // Intentionally left empty
    }
    return Optional.ofNullable(result);
  }

  @Override
  public void sendQueryTask(
      ExecutionId executionId, TaskQueueId taskQueue, PollForDecisionTaskResponse.Builder task) {
    lock.lock();
    try {
      HistoryStore historyStore = getHistoryStore(executionId);
      List<HistoryEvent> events = new ArrayList<>(historyStore.getEventsLocked());
      History.Builder history = History.newBuilder();
      if (taskQueue.getTaskQueueName().equals(task.getWorkflowExecutionTaskQueue().getName())) {
        history.addAllEvents(events);
      } else {
        history.addAllEvents(new ArrayList<>());
      }
      task.setHistory(history);
    } finally {
      lock.unlock();
    }
    BlockingQueue<PollForDecisionTaskResponse.Builder> decisionsQueue =
        getDecisionTaskQueueQueue(taskQueue);
    decisionsQueue.add(task);
  }

  @Override
  public GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(
      ExecutionId executionId, GetWorkflowExecutionHistoryRequest getRequest, Deadline deadline) {
    HistoryStore history;
    // Used to eliminate the race condition on waitForNewEvents
    long expectedNextEventId;
    lock.lock();
    try {
      history = getHistoryStore(executionId);
      if (!getRequest.getWaitForNewEvent()
          && getRequest.getHistoryEventFilterType()
              != HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT) {
        List<HistoryEvent> events = history.getEventsLocked();
        // Copy the list as it is mutable. Individual events assumed immutable.
        ArrayList<HistoryEvent> eventsCopy = new ArrayList<>(events);
        return GetWorkflowExecutionHistoryResponse.newBuilder()
            .setHistory(History.newBuilder().addAllEvents(eventsCopy))
            .build();
      }
      expectedNextEventId = history.getNextEventIdLocked();
    } finally {
      lock.unlock();
    }
    List<HistoryEvent> events =
        history.waitForNewEvents(
            expectedNextEventId, getRequest.getHistoryEventFilterType(), deadline);
    GetWorkflowExecutionHistoryResponse.Builder result =
        GetWorkflowExecutionHistoryResponse.newBuilder();
    if (events != null) {
      result.setHistory(History.newBuilder().addAllEvents(events));
    }
    return result.build();
  }

  private HistoryStore getHistoryStore(ExecutionId executionId) {
    HistoryStore result = histories.get(executionId);
    if (result == null) {
      WorkflowExecution execution = executionId.getExecution();
      throw Status.NOT_FOUND
          .withDescription(
              String.format(
                  "Workflow execution result not found.  " + "WorkflowId: %s, RunId: %s",
                  execution.getWorkflowId(), execution.getRunId()))
          .asRuntimeException();
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
    // Uncomment to troubleshoot time skipping issues.
    //    timerService.getDiagnostics(result);
  }

  @Override
  public List<WorkflowExecutionInfo> listWorkflows(
      WorkflowState state, Optional<String> filterWorkflowId) {
    List<WorkflowExecutionInfo> result = new ArrayList<>();
    for (Entry<ExecutionId, HistoryStore> entry : this.histories.entrySet()) {
      if (state == WorkflowState.OPEN) {
        if (entry.getValue().isCompleted()) {
          continue;
        }
        ExecutionId executionId = entry.getKey();
        String workflowId = executionId.getWorkflowId().getWorkflowId();
        if (filterWorkflowId.isPresent() && !workflowId.equals(filterWorkflowId.get())) {
          continue;
        }
        List<HistoryEvent> history = entry.getValue().getHistory();
        WorkflowExecutionInfo info =
            WorkflowExecutionInfo.newBuilder()
                .setExecution(executionId.getExecution())
                .setHistoryLength(history.size())
                .setStartTime(
                    Int64Value.newBuilder().setValue(history.get(0).getTimestamp()).build())
                .setType(
                    history.get(0).getWorkflowExecutionStartedEventAttributes().getWorkflowType())
                .build();
        result.add(info);
      } else {
        if (!entry.getValue().isCompleted()) {
          continue;
        }
        ExecutionId executionId = entry.getKey();
        String workflowId = executionId.getWorkflowId().getWorkflowId();
        if (filterWorkflowId.isPresent() && !workflowId.equals(filterWorkflowId.get())) {
          continue;
        }
        List<HistoryEvent> history = entry.getValue().getHistory();
        WorkflowExecutionInfo info =
            WorkflowExecutionInfo.newBuilder()
                .setExecution(executionId.getExecution())
                .setHistoryLength(history.size())
                .setStartTime(
                    Int64Value.newBuilder().setValue(history.get(0).getTimestamp()).build())
                .setType(
                    history.get(0).getWorkflowExecutionStartedEventAttributes().getWorkflowType())
                .setStatus(WorkflowExecutionUtils.getCloseStatus(history.get(history.size() - 1)))
                .build();
        result.add(info);
      }
    }
    return result;
  }

  @Override
  public void close() {
    timerService.shutdown();
  }
}
