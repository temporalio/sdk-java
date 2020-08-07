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

package io.temporal.internal.statemachines;

import static org.junit.Assert.assertEquals;

import com.google.common.base.Objects;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.google.protobuf.util.Timestamps;
import io.temporal.api.command.v1.Command;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.api.history.v1.TimerCanceledEventAttributes;
import io.temporal.api.history.v1.TimerFiredEventAttributes;
import io.temporal.api.history.v1.TimerStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionCompletedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskCompletedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskFailedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskScheduledEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskTimedOutEventAttributes;
import io.temporal.internal.common.WorkflowExecutionUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

class TestHistoryBuilder {
  private long eventId;
  private final List<HistoryEvent> events = new ArrayList<>();
  private long workflowTaskScheduledEventId;
  private long previousStartedEventId;

  private List<HistoryEvent> build() {
    return new ArrayList<>(events);
  }

  TestHistoryBuilder add(EventType type) {
    add(type, null);
    return this;
  }

  long addGetEventId(EventType type) {
    add(type, null);
    return eventId;
  }

  TestHistoryBuilder add(EventType type, Object attributes) {
    events.add(newAttributes(type, attributes));
    return this;
  }

  TestHistoryBuilder addWorkflowTask() {
    addWorkflowTaskScheduledAndStarted();
    addWorkflowTaskCompleted();
    return this;
  }

  TestHistoryBuilder addWorkflowTaskCompleted() {
    add(EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED, workflowTaskScheduledEventId);
    return this;
  }

  TestHistoryBuilder addWorkflowTaskScheduledAndStarted() {
    addWorkflowTaskScheduled();
    addWorkflowTaskStarted();
    return this;
  }

  TestHistoryBuilder addWorkflowTaskScheduled() {
    previousStartedEventId = workflowTaskScheduledEventId + 1;
    workflowTaskScheduledEventId = addGetEventId(EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED);
    return this;
  }

  TestHistoryBuilder addWorkflowTaskStarted() {
    add(EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED, workflowTaskScheduledEventId);
    return this;
  }

  TestHistoryBuilder addWorkflowTaskTimedOut() {
    add(EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT, workflowTaskScheduledEventId);
    return this;
  }

  public int getWorkflowTaskCount() {
    PeekingIterator<HistoryEvent> history = Iterators.peekingIterator(events.iterator());
    int result = 0;
    long started = 0;
    HistoryEvent event = null;
    while (true) {
      if (!history.hasNext()) {
        if (started != event.getEventId()) {
          throw new IllegalArgumentException(
              "The last event in the history is not WorkflowTaskStarted");
        }
        return result;
      }
      event = history.next();
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED
          && (!history.hasNext()
              || history.peek().getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED)) {
        started = event.getEventId();
        result++;
      }
    }
  }

  public static class HistoryInfo {
    private final long previousStartedEventId;
    private final long workflowTaskStartedEventId;

    public HistoryInfo(long previousStartedEventId, long workflowTaskStartedEventId) {
      this.previousStartedEventId = previousStartedEventId;
      this.workflowTaskStartedEventId = workflowTaskStartedEventId;
    }

    public long getPreviousStartedEventId() {
      return previousStartedEventId;
    }

    public long getWorkflowTaskStartedEventId() {
      return workflowTaskStartedEventId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      HistoryInfo that = (HistoryInfo) o;
      return previousStartedEventId == that.previousStartedEventId
          && workflowTaskStartedEventId == that.workflowTaskStartedEventId;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(previousStartedEventId, workflowTaskStartedEventId);
    }

    @Override
    public String toString() {
      return "HistoryInfo{"
          + "previousStartedEventId="
          + previousStartedEventId
          + ", workflowTaskStartedEventId="
          + workflowTaskStartedEventId
          + '}';
    }
  }

  public HistoryInfo getHistoryInfo() {
    return getHistoryInfo(Integer.MAX_VALUE);
  }

  public HistoryInfo getHistoryInfo(int toTaskIndex) {
    PeekingIterator<HistoryEvent> history = Iterators.peekingIterator(events.iterator());
    long previous = 0;
    long started = 0;
    HistoryEvent event = null;
    int count = 0;
    while (true) {
      if (!history.hasNext()) {
        if (WorkflowExecutionUtils.isWorkflowExecutionCompletedEvent(event)) {
          return new HistoryInfo(previous, started);
        }
        if (started != event.getEventId()) {
          throw new IllegalArgumentException(
              "The last event in the history is not WorkflowTaskStarted");
        }
        throw new IllegalStateException("unreachable");
      }
      event = history.next();
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED) {
        if (!history.hasNext()
            || history.peek().getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
          previous = started;
          started = event.getEventId();
          if (started == previous) {
            throw new IllegalStateException("equal started and previous: " + started);
          }
          count++;
          if (count == toTaskIndex || !history.hasNext()) {
            return new HistoryInfo(previous, started);
          }
        } else if (history.hasNext()
            && (history.peek().getEventType() != EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED
                && history.peek().getEventType() != EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT)) {
          throw new IllegalStateException(
              "Invalid history. Missing task completed, failed or timed out at "
                  + history.peek().getEventId());
        }
      }
    }
  }

  private int getWorkflowTaskCount(long upToEventId) {
    PeekingIterator<HistoryEvent> history = Iterators.peekingIterator(events.iterator());
    int result = 0;
    long started = 0;
    HistoryEvent event = null;
    while (true) {
      if (!history.hasNext()) {
        if (started != event.getEventId()) {
          throw new IllegalArgumentException(
              "The last event in the history is not WorkflowTaskStarted");
        }
        return result;
      }
      event = history.next();
      if (event.getEventId() > upToEventId) {
        return result;
      }
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED
          && (!history.hasNext()
              || history.peek().getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED)) {
        started = event.getEventId();
        result++;
      }
    }
  }

  List<Command> handleWorkflowTaskTakeCommands(WorkflowStateMachines manager) {
    return handleWorkflowTaskTakeCommands(manager, Integer.MAX_VALUE);
  }

  public List<Command> handleWorkflowTaskTakeCommands(
      WorkflowStateMachines manager, int toTaskIndex) {
    handleWorkflowTask(manager, toTaskIndex);
    return manager.takeCommands();
  }

  public void handleWorkflowTask(WorkflowStateMachines manager) {
    handleWorkflowTask(manager, Integer.MAX_VALUE);
  }

  public void handleWorkflowTask(WorkflowStateMachines manager, int toTaskIndex) {
    List<HistoryEvent> events =
        this.events.subList((int) manager.getLastStartedEventId(), this.events.size());
    PeekingIterator<HistoryEvent> history = Iterators.peekingIterator(events.iterator());
    HistoryInfo info = getHistoryInfo(toTaskIndex);
    manager.setStartedIds(info.getPreviousStartedEventId(), info.getWorkflowTaskStartedEventId());
    long started = info.getPreviousStartedEventId();
    HistoryEvent event = null;
    int count =
        manager.getLastStartedEventId() > 0
            ? getWorkflowTaskCount(history.peek().getEventId() - 1)
            : 0;
    while (true) {
      if (!history.hasNext()) {
        if (WorkflowExecutionUtils.isWorkflowExecutionCompletedEvent(event)) {
          return;
        }
        if (started != event.getEventId()) {
          throw new IllegalArgumentException(
              "The last event in the history is not WorkflowTaskStarted");
        }
        throw new IllegalStateException("unreachable");
      }
      event = history.next();
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED) {
        if (!history.hasNext()
            || history.peek().getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
          started = event.getEventId();
          count++;
          if (count == toTaskIndex || !history.hasNext()) {
            manager.handleEvent(event, false);
            return;
          }
        } else if (history.hasNext()
            && (history.peek().getEventType() != EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED
                && history.peek().getEventType() != EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT)) {
          throw new IllegalStateException(
              "Invalid history. Missing task completed, failed or timed out at "
                  + history.peek().getEventId());
        }
      }
      manager.handleEvent(event, history.hasNext());
    }
  }

  private Object newAttributes(EventType type, long initialEventId) {
    switch (type) {
      case EVENT_TYPE_WORKFLOW_EXECUTION_STARTED:
        return WorkflowExecutionStartedEventAttributes.getDefaultInstance();
      case EVENT_TYPE_WORKFLOW_TASK_SCHEDULED:
        return WorkflowTaskScheduledEventAttributes.getDefaultInstance();
      case EVENT_TYPE_WORKFLOW_TASK_STARTED:
        return WorkflowTaskStartedEventAttributes.newBuilder().setScheduledEventId(initialEventId);
      case EVENT_TYPE_WORKFLOW_TASK_COMPLETED:
        return WorkflowTaskCompletedEventAttributes.newBuilder()
            .setScheduledEventId(initialEventId);
      case EVENT_TYPE_TIMER_FIRED:
        return TimerFiredEventAttributes.newBuilder().setStartedEventId(initialEventId);
      case EVENT_TYPE_TIMER_STARTED:
        return TimerStartedEventAttributes.getDefaultInstance();
      case EVENT_TYPE_TIMER_CANCELED:
        return TimerCanceledEventAttributes.newBuilder().setStartedEventId(initialEventId);
      case EVENT_TYPE_MARKER_RECORDED:
        return MarkerRecordedEventAttributes.getDefaultInstance();
      case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
        return WorkflowExecutionCompletedEventAttributes.getDefaultInstance();
      case EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT:
        return WorkflowTaskTimedOutEventAttributes.newBuilder().setScheduledEventId(initialEventId);
      case EVENT_TYPE_WORKFLOW_TASK_FAILED:
        return WorkflowTaskFailedEventAttributes.newBuilder().setScheduledEventId(initialEventId);

      case EVENT_TYPE_UNSPECIFIED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
      case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED:
      case EVENT_TYPE_ACTIVITY_TASK_STARTED:
      case EVENT_TYPE_ACTIVITY_TASK_COMPLETED:
      case EVENT_TYPE_ACTIVITY_TASK_FAILED:
      case EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT:
      case EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED:
      case EVENT_TYPE_ACTIVITY_TASK_CANCELED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
      case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
      case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW:
      case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED:
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED:
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED:
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED:
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED:
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT:
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED:
      case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
      case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_SIGNALED:
      case EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
      case UNRECOGNIZED:
    }
    throw new IllegalArgumentException(type.name());
  }

  private HistoryEvent newAttributes(EventType type, Object attributes) {
    if (attributes == null) {
      attributes = newAttributes(type, 0);
    } else if (attributes instanceof Number) {
      attributes = newAttributes(type, ((Number) attributes).intValue());
    }
    if (attributes instanceof com.google.protobuf.GeneratedMessageV3.Builder) {
      attributes = ((com.google.protobuf.GeneratedMessageV3.Builder) attributes).build();
    }
    HistoryEvent.Builder result =
        HistoryEvent.newBuilder()
            .setEventTime(Timestamps.fromMillis(System.currentTimeMillis()))
            .setEventId(++eventId)
            .setEventType(type);
    if (attributes != null) {
      switch (type) {
        case EVENT_TYPE_WORKFLOW_EXECUTION_STARTED:
          result.setWorkflowExecutionStartedEventAttributes(
              (WorkflowExecutionStartedEventAttributes) attributes);
          break;
        case EVENT_TYPE_WORKFLOW_TASK_STARTED:
          result.setWorkflowTaskStartedEventAttributes(
              (WorkflowTaskStartedEventAttributes) attributes);
          break;
        case EVENT_TYPE_WORKFLOW_TASK_COMPLETED:
          result.setWorkflowTaskCompletedEventAttributes(
              (WorkflowTaskCompletedEventAttributes) attributes);
          break;
        case EVENT_TYPE_TIMER_FIRED:
          result.setTimerFiredEventAttributes((TimerFiredEventAttributes) attributes);
          break;
        case EVENT_TYPE_WORKFLOW_TASK_SCHEDULED:
          result.setWorkflowTaskScheduledEventAttributes(
              (WorkflowTaskScheduledEventAttributes) attributes);
          break;
        case EVENT_TYPE_TIMER_STARTED:
          result.setTimerStartedEventAttributes((TimerStartedEventAttributes) attributes);
          break;
        case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED:
          result.setWorkflowExecutionSignaledEventAttributes(
              (WorkflowExecutionSignaledEventAttributes) attributes);
          break;
        case EVENT_TYPE_TIMER_CANCELED:
          result.setTimerCanceledEventAttributes((TimerCanceledEventAttributes) attributes);
          break;
        case EVENT_TYPE_MARKER_RECORDED:
          result.setMarkerRecordedEventAttributes((MarkerRecordedEventAttributes) attributes);
          break;
        case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
          result.setWorkflowExecutionCompletedEventAttributes(
              (WorkflowExecutionCompletedEventAttributes) attributes);
          break;
        case EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT:
          result.setWorkflowTaskTimedOutEventAttributes(
              (WorkflowTaskTimedOutEventAttributes) attributes);
          break;
        case EVENT_TYPE_WORKFLOW_TASK_FAILED:
          result.setWorkflowTaskFailedEventAttributes(
              (WorkflowTaskFailedEventAttributes) attributes);
          break;

        case EVENT_TYPE_UNSPECIFIED:
        case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
        case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
        case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED:
        case EVENT_TYPE_ACTIVITY_TASK_STARTED:
        case EVENT_TYPE_ACTIVITY_TASK_COMPLETED:
        case EVENT_TYPE_ACTIVITY_TASK_FAILED:
        case EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT:
        case EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED:
        case EVENT_TYPE_ACTIVITY_TASK_CANCELED:
        case EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
        case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
        case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
        case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
        case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
        case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
        case EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW:
        case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED:
        case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED:
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED:
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED:
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED:
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED:
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT:
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED:
        case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
        case EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
        case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_SIGNALED:
        case EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES:
        case UNRECOGNIZED:
          throw new IllegalArgumentException(type.name());
      }
    }
    return result.build();
  }

  public long getPreviousStartedEventId() {
    return previousStartedEventId;
  }

  public long getWorkflowTaskStartedEventId() {
    return workflowTaskScheduledEventId + 1;
  }

  public TestHistoryBuilder nextTask() {
    TestHistoryBuilder result = new TestHistoryBuilder();
    result.eventId = eventId;
    result.workflowTaskScheduledEventId = workflowTaskScheduledEventId;
    result.previousStartedEventId = workflowTaskScheduledEventId;
    result.addWorkflowTaskCompleted();
    return result;
  }

  @Override
  public String toString() {
    return "HistoryBuilder{"
        + "eventId="
        + eventId
        + ", workflowTaskScheduledEventId="
        + workflowTaskScheduledEventId
        + ", previousStartedEventId="
        + previousStartedEventId
        + ", events=\n    "
        + eventsToString(events)
        + '}';
  }

  static String eventsToString(List<HistoryEvent> events) {
    return "    "
        + events.stream()
            .map((event) -> event.getEventId() + ": " + event.getEventType())
            .collect(Collectors.joining("\n    "));
  }

  static void assertCommand(CommandType type, List<Command> commands) {
    assertEquals(String.valueOf(commands), 1, commands.size());
    assertEquals(type, commands.get(0).getCommandType());
  }
}
