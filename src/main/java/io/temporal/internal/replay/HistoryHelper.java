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

import com.google.common.collect.PeekingIterator;
import com.google.protobuf.util.Timestamps;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.worker.WorkflowTaskWithHistoryIterator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

class HistoryHelper {

  /**
   * Events of a single workflow task. It includes all new events in the history since the last
   * workflow task as events. It doesn't include events that are events that correspond to commands
   * of the previous workflow task.
   */
  static final class WorkflowTaskEvents {

    private final List<HistoryEvent> events;
    private final List<HistoryEvent> commandEvents;
    private final List<HistoryEvent> markers = new ArrayList<>();
    private final boolean replay;
    private final long replayCurrentTimeMilliseconds;
    private final long nextCommandEventId;

    WorkflowTaskEvents(
        List<HistoryEvent> events,
        List<HistoryEvent> commandEvents,
        boolean replay,
        long replayCurrentTimeMilliseconds,
        long nextCommandEventId) {
      if (nextCommandEventId <= 0) {
        throw new Error("nextCommandEventId is not set");
      }
      this.events = events;
      this.commandEvents = commandEvents;
      for (HistoryEvent event : commandEvents) {
        if (event.getEventType() == EventType.EVENT_TYPE_MARKER_RECORDED) {
          markers.add(event);
        }
      }
      this.replay = replay;
      this.replayCurrentTimeMilliseconds = replayCurrentTimeMilliseconds;
      this.nextCommandEventId = nextCommandEventId;
    }

    public List<HistoryEvent> getEvents() {
      return events;
    }

    List<HistoryEvent> getCommandEvents() {
      return commandEvents;
    }

    /**
     * Returns event that was generated from a command.
     *
     * @return Optional#empty if event at that eventId is not a command originated event.
     */
    Optional<HistoryEvent> getCommandEvent(long eventId) {
      int index = (int) (eventId - nextCommandEventId);
      if (index < 0 || index >= commandEvents.size()) {
        return Optional.empty();
      }
      return Optional.of(commandEvents.get(index));
    }

    public List<HistoryEvent> getMarkers() {
      return markers;
    }

    public boolean isReplay() {
      return replay;
    }

    public long getReplayCurrentTimeMilliseconds() {
      return replayCurrentTimeMilliseconds;
    }

    public long getNextCommandEventId() {
      return nextCommandEventId;
    }

    @Override
    public String toString() {
      return "WorkflowTaskEvents{"
          + "events="
          + events
          + ", commandEvents="
          + commandEvents
          + ", markers="
          + markers
          + ", replay="
          + replay
          + ", replayCurrentTimeMilliseconds="
          + replayCurrentTimeMilliseconds
          + ", nextCommandEventId="
          + nextCommandEventId
          + '}';
    }
  }

  /** Allows peeking for the next event. */
  private static final class EventsIterator implements PeekingIterator<HistoryEvent> {

    private Iterator<HistoryEvent> events;
    private HistoryEvent next;

    EventsIterator(Iterator<HistoryEvent> events) {
      this.events = events;
      if (events.hasNext()) {
        next = events.next();
      }
    }

    @Override
    public HistoryEvent peek() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return next;
    }

    @Override
    public boolean hasNext() {
      return next != null;
    }

    @Override
    public HistoryEvent next() {
      HistoryEvent result = next;
      if (events.hasNext()) {
        next = events.next();
      } else {
        next = null;
      }
      return result;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("not implemented");
    }
  }

  /**
   * Iterates through workflow tasks in the history and returns a WorkflowTaskEvents instance per
   * WorkflowTaskStarted event.
   */
  private static class WorkflowTaskEventsIterator implements Iterator<WorkflowTaskEvents> {

    private EventsIterator events;
    private long replayCurrentTimeMilliseconds;

    WorkflowTaskEventsIterator(
        WorkflowTaskWithHistoryIterator workflowTaskWithHistoryIterator,
        long replayCurrentTimeMilliseconds) {
      this.events = new EventsIterator(workflowTaskWithHistoryIterator.getHistory());
      this.replayCurrentTimeMilliseconds = replayCurrentTimeMilliseconds;
    }

    @Override
    public boolean hasNext() {
      return events.hasNext();
    }

    @Override
    public WorkflowTaskEvents next() {
      // Events generated from commands.
      //
      // For example: ScheduleActivityTaskCommand -> ActivityTaskScheduledEvent
      List<HistoryEvent> commandEvents = new ArrayList<>();
      List<HistoryEvent> newEvents = new ArrayList<>();
      boolean replay = true;
      long nextCommandEventId = -1;
      while (events.hasNext()) {
        HistoryEvent event = events.next();
        EventType eventType = event.getEventType();

        // Sticky workers receive an event history that starts with WorkflowTaskCompleted
        if (eventType == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED && nextCommandEventId == -1) {
          nextCommandEventId = event.getEventId() + 1;
          break;
        }

        if (eventType == EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED || !events.hasNext()) {
          replayCurrentTimeMilliseconds = Timestamps.toMillis(event.getEventTime());
          if (!events.hasNext()) {
            replay = false;
            nextCommandEventId =
                event.getEventId() + 2; // +1 for next, +1 for WorkflowTaskCompleted
            break;
          }
          HistoryEvent peeked = events.peek();
          EventType peekedType = peeked.getEventType();
          if (peekedType == EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT
              || peekedType == EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED) {
            continue;
          } else if (peekedType == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
            events.next(); // consume WorkflowTaskCompleted
            nextCommandEventId = peeked.getEventId() + 1; // +1 for next and skip over completed
            break;
          } else {
            throw new Error(
                "Unexpected event after WorkflowTaskStarted: "
                    + peeked
                    + " WorkflowTaskStarted Event: "
                    + event);
          }
        }
        newEvents.add(event);
      }
      while (events.hasNext()) {
        if (!WorkflowExecutionUtils.isCommandEvent(events.peek())) {
          break;
        }
        commandEvents.add(events.next());
      }
      WorkflowTaskEvents result =
          new WorkflowTaskEvents(
              newEvents, commandEvents, replay, replayCurrentTimeMilliseconds, nextCommandEventId);
      return result;
    }
  }

  private final WorkflowTaskWithHistoryIterator workflowTaskWithHistoryIterator;
  private final Iterator<WorkflowTaskEvents> iterator;

  HistoryHelper(WorkflowTaskWithHistoryIterator workflowTasks, long replayCurrentTimeMilliseconds) {
    this.workflowTaskWithHistoryIterator = workflowTasks;
    this.iterator = new WorkflowTaskEventsIterator(workflowTasks, replayCurrentTimeMilliseconds);
  }

  public Iterator<WorkflowTaskEvents> getIterator() {
    return iterator;
  }

  public PollWorkflowTaskQueueResponseOrBuilder getWorkflowTask() {
    return workflowTaskWithHistoryIterator.getWorkflowTask();
  }

  @Override
  public String toString() {
    return WorkflowExecutionUtils.prettyPrintHistory(
        workflowTaskWithHistoryIterator.getWorkflowTask().getHistory().getEventsList().iterator(),
        true);
  }

  long getPreviousStartedEventId() {
    return getWorkflowTask().getPreviousStartedEventId();
  }
}
