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

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.grpc.Deadline;
import io.grpc.Status;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.HistoryEventFilterType;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.taskqueue.v1.StickyExecutionAttributes;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.testservice.RequestContext.Timer;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TestWorkflowStoreImpl implements TestWorkflowStore {

  private static final Logger log = LoggerFactory.getLogger(TestWorkflowStoreImpl.class);

  private final Lock lock = new ReentrantLock();
  private final Map<ExecutionId, HistoryStore> histories = new HashMap<>();
  private final Map<TaskQueueId, TaskQueue<PollActivityTaskQueueResponse.Builder>>
      activityTaskQueues = new HashMap<>();
  private final Map<TaskQueueId, TaskQueue<PollWorkflowTaskQueueResponse.Builder>>
      workflowTaskQueues = new HashMap<>();
  private final Map<TaskQueueId, TaskQueue<PollNexusTaskQueueResponse.Builder>> nexusTaskQueues =
      new HashMap<>();
  private final SelfAdvancingTimer selfAdvancingTimer;

  private static class HistoryStore {

    private final ExecutionId id;
    private final Lock lock;
    private final Condition newEventsCondition;
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

    void addAllLocked(List<HistoryEvent> events, Timestamp eventTime) {
      for (HistoryEvent event : events) {
        HistoryEvent.Builder eBuilder = event.toBuilder();
        if (completed) {
          throw ApplicationFailure.newNonRetryableFailure("Workflow execution completed.", "test");
        }
        eBuilder.setEventId(history.size() + 1L);
        // It can be set in StateMachines.startActivityTask
        if (Timestamps.toMillis(eBuilder.getEventTime()) == 0) {
          eBuilder.setEventTime(eventTime);
        }
        history.add(eBuilder.build());
        completed = completed || WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(eBuilder);
      }
      newEventsCondition.signalAll();
    }

    long getNextEventIdLocked() {
      return history.size() + 1L;
    }

    List<HistoryEvent> getEventsLocked() {
      return history;
    }

    List<HistoryEvent> waitForNewEvents(
        long expectedNextEventId, HistoryEventFilterType filterType, Deadline deadline) {
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
              toWait = deadline.timeRemaining(TimeUnit.MILLISECONDS);
              if (toWait <= 0) {
                return null;
              }
              newEventsCondition.await(toWait, TimeUnit.MILLISECONDS);
            } else {
              newEventsCondition.await();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
          }
        }
      } finally {
        lock.unlock();
      }
    }
  }

  public TestWorkflowStoreImpl(SelfAdvancingTimer selfAdvancingTimer) {
    this.selfAdvancingTimer = selfAdvancingTimer;
  }

  @Override
  public Timestamp currentTime() {
    return Timestamps.fromMillis(selfAdvancingTimer.getClock().getAsLong());
  }

  @Override
  public long save(RequestContext ctx) {
    long result;
    lock.lock();
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
      history.addAllLocked(events, ctx.currentTime());
      result = history.getNextEventIdLocked();
      selfAdvancingTimer.updateLocks(ctx.getTimerLocks());
      ctx.fireCallbacks(history.getEventsLocked().size());
    } finally {
      lock.unlock();
    }
    // Push tasks to the queues out of locks
    WorkflowTask workflowTask = ctx.getWorkflowTaskForMatching();

    if (workflowTask != null) {
      StickyExecutionAttributes attributes =
          ctx.getWorkflowMutableState().getStickyExecutionAttributes();
      TaskQueueId id =
          new TaskQueueId(
              workflowTask.getTaskQueueId().getNamespace(),
              attributes == null
                  ? workflowTask.getTaskQueueId().getTaskQueueName()
                  : attributes.getWorkerTaskQueue().getName());
      if (id.getTaskQueueName().isEmpty() || id.getNamespace().isEmpty()) {
        throw Status.INTERNAL.withDescription("Invalid TaskQueueId: " + id).asRuntimeException();
      }
      TaskQueue<PollWorkflowTaskQueueResponse.Builder> workflowTaskQueue =
          getWorkflowTaskQueueQueue(id);
      workflowTaskQueue.add(workflowTask.getTask());
    }

    List<ActivityTask> activityTasks = ctx.getActivityTasks();
    if (activityTasks != null) {
      for (ActivityTask activityTask : activityTasks) {
        TaskQueue<PollActivityTaskQueueResponse.Builder> activityTaskQueue =
            getActivityTaskQueueQueue(activityTask.getTaskQueueId());
        activityTaskQueue.add(activityTask.getTask());
      }
    }

    List<NexusTask> nexusTasks = ctx.getNexusTasks();
    if (nexusTasks != null) {
      for (NexusTask nexusTask : nexusTasks) {
        TaskQueue<PollNexusTaskQueueResponse.Builder> nexusTaskQueue =
            getNexusTaskQueueQueue(nexusTask.getTaskQueueId());
        nexusTaskQueue.add(nexusTask.getTask());
      }
    }

    List<Timer> timers = ctx.getTimers();
    if (timers != null) {
      for (Timer t : timers) {
        log.trace(
            "scheduling timer with " + t.getDelay() + "delay. Current time=" + this.currentTime());
        Functions.Proc cancellationHandle =
            selfAdvancingTimer.schedule(t.getDelay(), t.getCallback(), t.getTaskInfo());
        t.setCancellationHandle(cancellationHandle);
      }
    }
    return result;
  }

  @Override
  public void applyTimersAndLocks(RequestContext ctx) {
    lock.lock();
    try {
      selfAdvancingTimer.updateLocks(ctx.getTimerLocks());
    } finally {
      lock.unlock();
    }

    List<Timer> timers = ctx.getTimers();
    if (timers != null) {
      for (Timer t : timers) {
        Functions.Proc cancellationHandle =
            selfAdvancingTimer.schedule(t.getDelay(), t.getCallback(), t.getTaskInfo());
        t.setCancellationHandle(cancellationHandle);
      }
    }

    ctx.clearTimersAndLocks();
  }

  @Override
  public void registerDelayedCallback(Duration delay, Runnable r) {
    selfAdvancingTimer.schedule(delay, r, "registerDelayedCallback");
  }

  private TaskQueue<PollActivityTaskQueueResponse.Builder> getActivityTaskQueueQueue(
      TaskQueueId taskQueueId) {
    lock.lock();
    try {
      TaskQueue<PollActivityTaskQueueResponse.Builder> activityTaskQueue =
          activityTaskQueues.get(taskQueueId);
      if (activityTaskQueue == null) {
        activityTaskQueue = new TaskQueue<>();
        activityTaskQueues.put(taskQueueId, activityTaskQueue);
      }
      return activityTaskQueue;
    } finally {
      lock.unlock();
    }
  }

  private TaskQueue<PollWorkflowTaskQueueResponse.Builder> getWorkflowTaskQueueQueue(
      TaskQueueId taskQueueId) {
    lock.lock();
    try {
      TaskQueue<PollWorkflowTaskQueueResponse.Builder> workflowTaskQueue =
          workflowTaskQueues.get(taskQueueId);
      if (workflowTaskQueue == null) {
        workflowTaskQueue = new TaskQueue<>();
        workflowTaskQueues.put(taskQueueId, workflowTaskQueue);
      }
      return workflowTaskQueue;
    } finally {
      lock.unlock();
    }
  }

  private TaskQueue<PollNexusTaskQueueResponse.Builder> getNexusTaskQueueQueue(
      TaskQueueId taskQueueId) {
    lock.lock();
    try {
      TaskQueue<PollNexusTaskQueueResponse.Builder> nexusTaskQueue =
          nexusTaskQueues.get(taskQueueId);
      if (nexusTaskQueue == null) {
        nexusTaskQueue = new TaskQueue<>();
        nexusTaskQueues.put(taskQueueId, nexusTaskQueue);
      }
      return nexusTaskQueue;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Future<PollWorkflowTaskQueueResponse.Builder> pollWorkflowTaskQueue(
      PollWorkflowTaskQueueRequest pollRequest) {
    final TaskQueueId taskQueueId =
        new TaskQueueId(pollRequest.getNamespace(), pollRequest.getTaskQueue().getName());
    return getWorkflowTaskQueueQueue(taskQueueId).poll();
  }

  @Override
  public Future<PollActivityTaskQueueResponse.Builder> pollActivityTaskQueue(
      PollActivityTaskQueueRequest pollRequest) {
    final TaskQueueId taskQueueId =
        new TaskQueueId(pollRequest.getNamespace(), pollRequest.getTaskQueue().getName());
    return getActivityTaskQueueQueue(taskQueueId).poll();
  }

  @Override
  public Future<PollNexusTaskQueueResponse.Builder> pollNexusTaskQueue(
      PollNexusTaskQueueRequest pollRequest) {
    final TaskQueueId taskQueueId =
        new TaskQueueId(pollRequest.getNamespace(), pollRequest.getTaskQueue().getName());
    return getNexusTaskQueueQueue(taskQueueId).poll();
  }

  @Override
  public void sendQueryTask(
      ExecutionId executionId, TaskQueueId taskQueue, PollWorkflowTaskQueueResponse.Builder task) {
    lock.lock();
    try {
      HistoryStore historyStore = getHistoryStore(executionId);
      List<HistoryEvent> events = new ArrayList<>(historyStore.getEventsLocked());
      History.Builder history = History.newBuilder();
      PeekingIterator<HistoryEvent> iterator = Iterators.peekingIterator(events.iterator());
      long previousStaredEventId = 0;
      while (iterator.hasNext()) {
        HistoryEvent event = iterator.next();
        if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED) {
          if (!iterator.hasNext()
              || iterator.peek().getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
            previousStaredEventId = event.getEventId();
          }
        } else if (WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(event)) {
          if (iterator.hasNext()) {
            throw Status.INTERNAL
                .withDescription("Unexpected event after the completion event: " + iterator.peek())
                .asRuntimeException();
          }
        }
      }
      task.setPreviousStartedEventId(previousStaredEventId);
      // it's not a real workflow task and the server sends 0 for startedEventId for such a workflow
      // task
      task.setStartedEventId(0);
      if (taskQueue.getTaskQueueName().equals(task.getWorkflowExecutionTaskQueue().getName())) {
        history.addAllEvents(events);
      } else {
        history.addAllEvents(new ArrayList<>());
      }
      task.setHistory(history);
    } finally {
      lock.unlock();
    }
    TaskQueue<PollWorkflowTaskQueueResponse.Builder> workflowTaskQueue =
        getWorkflowTaskQueueQueue(taskQueue);
    workflowTaskQueue.add(task);
  }

  @Override
  public GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(
      ExecutionId executionId,
      GetWorkflowExecutionHistoryRequest getRequest,
      Deadline deadlineToReturnEmptyResponse) {
    HistoryStore history;
    // Used to eliminate the race condition on waitForNewEvents
    long expectedNextEventId;
    lock.lock();
    try {
      history = getHistoryStore(executionId);
      if (!getRequest.getWaitNewEvent()) {
        List<HistoryEvent> events = history.getEventsLocked();
        // Copy the list as it is mutable. Individual events assumed immutable.
        List<HistoryEvent> eventsCopy =
            events.stream()
                .filter(
                    e -> {
                      if (getRequest.getHistoryEventFilterType()
                          != HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT) {
                        return true;
                      }

                      // They asked for only the close event. There are a variety of ways a workflow
                      // can close.
                      return WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(e);
                    })
                .collect(Collectors.toList());
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
            expectedNextEventId,
            getRequest.getHistoryEventFilterType(),
            deadlineToReturnEmptyResponse);
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
          result.append("\n\n");
          result.append(
              new WorkflowExecutionHistory(
                      History.newBuilder().addAllEvents(entry.getValue().getEventsLocked()).build())
                  .toProtoText(true));
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
      ExecutionId executionId = entry.getKey();
      String workflowId = executionId.getWorkflowId().getWorkflowId();
      if (filterWorkflowId.isPresent() && !workflowId.equals(filterWorkflowId.get())) {
        continue;
      }

      if (state == WorkflowState.OPEN) {
        if (entry.getValue().isCompleted()) {
          continue;
        }
        result.add(
            constructWorkflowExecutionInfo(
                entry, executionId, WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING));
      } else {
        if (!entry.getValue().isCompleted()) {
          continue;
        }
        List<HistoryEvent> history = entry.getValue().getHistory();
        WorkflowExecutionStatus status =
            WorkflowExecutionUtils.getCloseStatus(history.get(history.size() - 1));
        result.add(constructWorkflowExecutionInfo(entry, executionId, status));
      }
    }
    return result;
  }

  private WorkflowExecutionInfo constructWorkflowExecutionInfo(
      Entry<ExecutionId, HistoryStore> entry,
      ExecutionId executionId,
      WorkflowExecutionStatus status) {
    List<HistoryEvent> history = entry.getValue().getHistory();
    WorkflowExecutionInfo.Builder info =
        WorkflowExecutionInfo.newBuilder()
            .setExecution(executionId.getExecution())
            .setHistoryLength(history.size())
            .setStartTime(history.get(0).getEventTime())
            .setType(history.get(0).getWorkflowExecutionStartedEventAttributes().getWorkflowType());
    if (status != null) {
      info.setStatus(status);
    }
    return info.build();
  }

  @Override
  public void close() {
    selfAdvancingTimer.shutdown();
  }
}
