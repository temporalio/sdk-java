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

package io.temporal.internal.statemachines;

import static org.junit.Assert.assertEquals;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import com.google.protobuf.util.Timestamps;
import io.temporal.api.command.v1.Command;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.*;
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

  History getHistory() {
    return History.newBuilder().addAllEvents(events).build();
  }

  long addGetEventId(EventType type) {
    return addGetEventId(type, null);
  }

  long addGetEventId(EventType type, Object attributes) {
    add(type, attributes);
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

  /**
   * @return HistoryInfo for a full replay from history
   */
  public HistoryInfo getHistoryInfo() {
    return getHistoryInfo(Integer.MAX_VALUE);
  }

  /**
   * @param replayToIndex index (starting from 1, 0 means no replays) of workflow task that should
   *     be last for replay. Indexes start from 1. 0 means no replay, {@code Integer.MAX_VALUE}
   *     means full replay
   * @return {@link HistoryInfo#previousStartedEventId} contains an EventId of WorkflowTaskStated
   *     event with {@code replayToIndex} index. {@link HistoryInfo#workflowTaskStartedEventId}
   *     contains an EventId of WorkflowTaskStated event with {@code replayToIndex + 1} index. <br>
   *     If the history is a full workflow execution, and it's shorter than requested {@code
   *     replayToIndex}, {@link HistoryInfo#workflowTaskStartedEventId} will be {@code
   *     Integer.MAX_VALUE} and {@link HistoryInfo#previousStartedEventId} will be an EventId of the
   *     last WorkflowTaskStarted in the history. <br>
   *     If the history is a non-finished workflow execution, and it's shorter than requested {@code
   *     replayToIndex}, {@link HistoryInfo#workflowTaskStartedEventId} will be an EventId of the
   *     last WorkflowTaskStarted in the history and {@link HistoryInfo#previousStartedEventId} will
   *     be an EventId of the previous WorkflowTaskStarted.
   */
  public HistoryInfo getHistoryInfo(int replayToIndex) {
    int executeFromIndex =
        replayToIndex < Integer.MAX_VALUE ? replayToIndex + 1 : Integer.MAX_VALUE;
    PeekingIterator<HistoryEvent> history = Iterators.peekingIterator(events.iterator());
    long previous = 0;
    long started = 0;
    HistoryEvent event = null;
    int count = 0;
    while (true) {
      if (!history.hasNext()) {
        if (WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(event)) {
          // we didn't reach replayToIndex for replays before the end of the history,
          // return "full replay"
          return new HistoryInfo(started, Integer.MAX_VALUE);
        }
        if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED) {
          // the history ends with WorkflowTaskStarted event, in that case the last WFT that we can
          // replay is previous
          return new HistoryInfo(previous, started);
        }
        if (started != event.getEventId()) {
          throw new IllegalArgumentException(
              "The last event in the history is not WorkflowTaskStarted and not one of Workflow Execution Closing events");
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
          if (count == executeFromIndex) {
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

  List<Command> handleWorkflowTaskTakeCommands(WorkflowStateMachines stateMachines) {
    return handleWorkflowTaskTakeCommands(stateMachines, Integer.MAX_VALUE, Integer.MAX_VALUE);
  }

  public List<Command> handleWorkflowTaskTakeCommands(
      WorkflowStateMachines stateMachines, int executeToTaskIndex) {
    handleWorkflowTask(stateMachines, executeToTaskIndex);
    return stateMachines.takeCommands();
  }

  public List<Command> handleWorkflowTaskTakeCommands(
      WorkflowStateMachines stateMachines, int replayToTaskIndex, int executeToTaskIndex) {
    handleWorkflowTask(stateMachines, replayToTaskIndex, executeToTaskIndex);
    return stateMachines.takeCommands();
  }

  public void handleWorkflowTask(WorkflowStateMachines stateMachines) {
    handleWorkflowTask(stateMachines, Integer.MAX_VALUE, Integer.MAX_VALUE);
  }

  public void handleWorkflowTask(WorkflowStateMachines stateMachines, int executeToTaskIndex) {
    handleWorkflowTask(stateMachines, executeToTaskIndex - 1, executeToTaskIndex);
  }

  public void handleWorkflowTask(
      WorkflowStateMachines stateMachines, int replayToTaskIndex, int executeToTaskIndex) {
    Preconditions.checkState(replayToTaskIndex <= executeToTaskIndex);
    List<HistoryEvent> events =
        this.events.subList((int) stateMachines.getLastStartedEventId(), this.events.size());
    PeekingIterator<HistoryEvent> history = Iterators.peekingIterator(events.iterator());
    HistoryInfo info = getHistoryInfo(replayToTaskIndex);
    stateMachines.setWorkflowStartedEventId(info.getWorkflowTaskStartedEventId());
    stateMachines.setReplaying(info.getPreviousStartedEventId() > 0);

    long wftStartedEventId = -1;
    HistoryEvent event = null;
    int count =
        stateMachines.getLastStartedEventId() > 0
            ? getWorkflowTaskCount(history.peek().getEventId() - 1)
            : 0;
    while (true) {
      if (!history.hasNext()) {
        if (WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(event)) {
          return;
        }
        if (wftStartedEventId != -1 && wftStartedEventId != event.getEventId()) {
          throw new IllegalArgumentException(
              "The last event in the history is not WorkflowTaskStarted and not completed");
        }
        throw new IllegalStateException("unreachable");
      }
      event = history.next();
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED) {
        if (!history.hasNext()
            || history.peek().getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
          wftStartedEventId = event.getEventId();
          count++;
          if (count == executeToTaskIndex || !history.hasNext()) {
            stateMachines.handleEvent(event, false);
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
      stateMachines.handleEvent(event, history.hasNext());
    }
  }

  private Object newAttributes(EventType type, long initiatedEventId) {
    switch (type) {
      case EVENT_TYPE_WORKFLOW_EXECUTION_STARTED:
        return WorkflowExecutionStartedEventAttributes.getDefaultInstance();
      case EVENT_TYPE_WORKFLOW_TASK_SCHEDULED:
        return WorkflowTaskScheduledEventAttributes.getDefaultInstance();
      case EVENT_TYPE_WORKFLOW_TASK_STARTED:
        return WorkflowTaskStartedEventAttributes.newBuilder()
            .setScheduledEventId(initiatedEventId);
      case EVENT_TYPE_WORKFLOW_TASK_COMPLETED:
        return WorkflowTaskCompletedEventAttributes.newBuilder()
            .setScheduledEventId(initiatedEventId);
      case EVENT_TYPE_TIMER_FIRED:
        return TimerFiredEventAttributes.newBuilder().setStartedEventId(initiatedEventId);
      case EVENT_TYPE_TIMER_STARTED:
        return TimerStartedEventAttributes.getDefaultInstance();
      case EVENT_TYPE_TIMER_CANCELED:
        return TimerCanceledEventAttributes.newBuilder().setStartedEventId(initiatedEventId);
      case EVENT_TYPE_MARKER_RECORDED:
        return MarkerRecordedEventAttributes.getDefaultInstance();
      case EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED:
        return WorkflowExecutionCompletedEventAttributes.getDefaultInstance();
      case EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT:
        return WorkflowTaskTimedOutEventAttributes.newBuilder()
            .setScheduledEventId(initiatedEventId);
      case EVENT_TYPE_WORKFLOW_TASK_FAILED:
        return WorkflowTaskFailedEventAttributes.newBuilder().setScheduledEventId(initiatedEventId);
      case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED:
        return ActivityTaskScheduledEventAttributes.getDefaultInstance();
      case EVENT_TYPE_ACTIVITY_TASK_STARTED:
        return ActivityTaskStartedEventAttributes.newBuilder()
            .setScheduledEventId(initiatedEventId);
      case EVENT_TYPE_ACTIVITY_TASK_COMPLETED:
        return ActivityTaskCompletedEventAttributes.newBuilder()
            .setScheduledEventId(initiatedEventId);
      case EVENT_TYPE_ACTIVITY_TASK_FAILED:
        return ActivityTaskFailedEventAttributes.newBuilder().setScheduledEventId(initiatedEventId);
      case EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT:
        return ActivityTaskTimedOutEventAttributes.newBuilder()
            .setScheduledEventId(initiatedEventId);
      case EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED:
        return ActivityTaskCancelRequestedEventAttributes.newBuilder()
            .setScheduledEventId(initiatedEventId);
      case EVENT_TYPE_ACTIVITY_TASK_CANCELED:
        return ActivityTaskCanceledEventAttributes.newBuilder()
            .setScheduledEventId(initiatedEventId);
      case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED:
        return StartChildWorkflowExecutionInitiatedEventAttributes.getDefaultInstance();
      case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED:
        return StartChildWorkflowExecutionFailedEventAttributes.newBuilder()
            .setInitiatedEventId(initiatedEventId);
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED:
        return ChildWorkflowExecutionStartedEventAttributes.newBuilder()
            .setInitiatedEventId(initiatedEventId);
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED:
        return ChildWorkflowExecutionCompletedEventAttributes.newBuilder()
            .setInitiatedEventId(initiatedEventId);
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED:
        return ChildWorkflowExecutionFailedEventAttributes.newBuilder()
            .setInitiatedEventId(initiatedEventId);
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED:
        return ChildWorkflowExecutionCanceledEventAttributes.newBuilder()
            .setInitiatedEventId(initiatedEventId);
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT:
        return ChildWorkflowExecutionTimedOutEventAttributes.newBuilder()
            .setInitiatedEventId(initiatedEventId);
      case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED:
        return ChildWorkflowExecutionTerminatedEventAttributes.newBuilder()
            .setInitiatedEventId(initiatedEventId);
      case EVENT_TYPE_UNSPECIFIED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
      case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
      case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
      case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
      case EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW:
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
        case EVENT_TYPE_ACTIVITY_TASK_SCHEDULED:
          result.setActivityTaskScheduledEventAttributes(
              (ActivityTaskScheduledEventAttributes) attributes);
          break;
        case EVENT_TYPE_ACTIVITY_TASK_STARTED:
          result.setActivityTaskStartedEventAttributes(
              (ActivityTaskStartedEventAttributes) attributes);
          break;
        case EVENT_TYPE_ACTIVITY_TASK_COMPLETED:
          result.setActivityTaskCompletedEventAttributes(
              (ActivityTaskCompletedEventAttributes) attributes);
          break;
        case EVENT_TYPE_ACTIVITY_TASK_FAILED:
          result.setActivityTaskFailedEventAttributes(
              (ActivityTaskFailedEventAttributes) attributes);
          break;
        case EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT:
          result.setActivityTaskTimedOutEventAttributes(
              (ActivityTaskTimedOutEventAttributes) attributes);
          break;
        case EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED:
          result.setActivityTaskCancelRequestedEventAttributes(
              (ActivityTaskCancelRequestedEventAttributes) attributes);
          break;
        case EVENT_TYPE_ACTIVITY_TASK_CANCELED:
          result.setActivityTaskCanceledEventAttributes(
              (ActivityTaskCanceledEventAttributes) attributes);
          break;
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED:
          result.setChildWorkflowExecutionStartedEventAttributes(
              (ChildWorkflowExecutionStartedEventAttributes) attributes);
          break;
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED:
          result.setChildWorkflowExecutionCompletedEventAttributes(
              (ChildWorkflowExecutionCompletedEventAttributes) attributes);
          break;
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED:
          result.setChildWorkflowExecutionFailedEventAttributes(
              (ChildWorkflowExecutionFailedEventAttributes) attributes);
          break;
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED:
          result.setChildWorkflowExecutionCanceledEventAttributes(
              (ChildWorkflowExecutionCanceledEventAttributes) attributes);
          break;
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT:
          result.setChildWorkflowExecutionTimedOutEventAttributes(
              (ChildWorkflowExecutionTimedOutEventAttributes) attributes);
          break;
        case EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED:
          result.setChildWorkflowExecutionTerminatedEventAttributes(
              (ChildWorkflowExecutionTerminatedEventAttributes) attributes);
          break;
        case EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED:
          result.setWorkflowExecutionUpdateAdmittedEventAttributes(
              (WorkflowExecutionUpdateAdmittedEventAttributes) attributes);
          break;
        case EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED:
          result.setWorkflowExecutionUpdateAcceptedEventAttributes(
              (WorkflowExecutionUpdateAcceptedEventAttributes) attributes);
          break;
        case EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED:
          result.setWorkflowExecutionUpdateCompletedEventAttributes(
              (WorkflowExecutionUpdateCompletedEventAttributes) attributes);
          break;
        case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED:
          result.setStartChildWorkflowExecutionInitiatedEventAttributes(
              (StartChildWorkflowExecutionInitiatedEventAttributes) attributes);
          break;
        case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED:
          result.setRequestCancelExternalWorkflowExecutionInitiatedEventAttributes(
              (RequestCancelExternalWorkflowExecutionInitiatedEventAttributes) attributes);
          break;
        case EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
          result.setExternalWorkflowExecutionCancelRequestedEventAttributes(
              (ExternalWorkflowExecutionCancelRequestedEventAttributes) attributes);
          break;
        case EVENT_TYPE_NEXUS_OPERATION_SCHEDULED:
          result.setNexusOperationScheduledEventAttributes(
              (NexusOperationScheduledEventAttributes) attributes);
          break;
        case EVENT_TYPE_NEXUS_OPERATION_STARTED:
          result.setNexusOperationStartedEventAttributes(
              (NexusOperationStartedEventAttributes) attributes);
          break;
        case EVENT_TYPE_NEXUS_OPERATION_COMPLETED:
          result.setNexusOperationCompletedEventAttributes(
              (NexusOperationCompletedEventAttributes) attributes);
          break;
        case EVENT_TYPE_NEXUS_OPERATION_CANCELED:
          result.setNexusOperationCanceledEventAttributes(
              (NexusOperationCanceledEventAttributes) attributes);
          break;
        case EVENT_TYPE_NEXUS_OPERATION_FAILED:
          result.setNexusOperationFailedEventAttributes(
              (NexusOperationFailedEventAttributes) attributes);
          break;
        case EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT:
          result.setNexusOperationTimedOutEventAttributes(
              (NexusOperationTimedOutEventAttributes) attributes);
          break;
        case EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED:
          result.setNexusOperationCancelRequestedEventAttributes(
              (NexusOperationCancelRequestedEventAttributes) attributes);
          break;
        case EVENT_TYPE_UNSPECIFIED:
        case EVENT_TYPE_WORKFLOW_EXECUTION_FAILED:
        case EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT:
        case EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED:
        case EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED:
        case EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED:
        case EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED:
        case EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW:
        case EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED:
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
        + ", events=\n"
        + eventsToString(events)
        + "\n}";
  }

  static String eventsToString(List<HistoryEvent> events) {
    return "        "
        + events.stream()
            .map((event) -> event.getEventId() + ": " + event.getEventType())
            .collect(Collectors.joining("\n        "));
  }

  static void assertCommand(CommandType type, List<Command> commands) {
    assertEquals(String.valueOf(commands), 1, commands.size());
    assertEquals(type, commands.get(0).getCommandType());
  }
}
