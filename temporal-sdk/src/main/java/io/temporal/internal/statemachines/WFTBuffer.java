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

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.internal.common.WorkflowExecutionUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * This class buffers events between WorkflowTaskStarted events and return them in one chunk so any
 * kinds of look ahead and preloading can be implemented.
 */
public class WFTBuffer {
  private enum WFTState {
    // we are not "inside" a started task
    None,
    // we saw a starting event and now expecting a closing event
    Started,
    // we saw a closing event and now expecting command events
    Closed,
  }

  public static class EventBatch {
    private final List<HistoryEvent> events;
    private final Optional<HistoryEvent> workflowTaskCompletedEvent;

    public EventBatch(
        Optional<HistoryEvent> workflowTaskCompletedEvent, List<HistoryEvent> events) {
      this.workflowTaskCompletedEvent = workflowTaskCompletedEvent;
      this.events = events;
    }

    public List<HistoryEvent> getEvents() {
      return events;
    }

    public Optional<HistoryEvent> getWorkflowTaskCompletedEvent() {
      return workflowTaskCompletedEvent;
    }
  }

  private WFTState wftSequenceState = WFTState.None;

  private final List<HistoryEvent> wftBuffer = new ArrayList<>();
  private Optional<HistoryEvent> workflowTaskCompletedEvent = Optional.empty();
  private final List<HistoryEvent> readyToFetch = new ArrayList<>();

  /**
   * @return Should the buffer be fetched. true if a whole history for a workflow task is
   * accumulated or events can't be attributed to a completed workflow task. The whole history
   * includes the unprocessed history events before the WorkflowTaskStarted and the 
   * command events after the WorkflowTaskCompleted. 
   */
  public boolean addEvent(HistoryEvent event, boolean hasNextEvent) {
    if (readyToFetch.size() > 0) {
      throw new IllegalStateException("Can't add more events until the readyToFetch is fetched");
    }
    handleEvent(event, hasNextEvent);
    return !readyToFetch.isEmpty();
  }

  private void handleEvent(HistoryEvent event, boolean hasNextEvent) {
    if (!hasNextEvent) {
      // flush buffer
      flushBuffer();

      // If the last event in history is a WORKFLOW_TASK_COMPLETED, because say we received a direct query,
      // we need to return it as a batch.
      if (WFTState.Started.equals(wftSequenceState)
          && event.getEventType().equals(EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED)) {
        workflowTaskCompletedEvent = Optional.of(event);
      }
      // exit the sequence
      wftSequenceState = WFTState.None;
      readyToFetch.add(event);
      return;
    }

    // This is the only way to enter into the WFT sequence -
    // any WorkflowTaskStarted event that it not the last in the history
    if (EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED.equals(event.getEventType())) {
      // Init a new sequence
      wftSequenceState = WFTState.Started;
      addToBuffer(event);
      return;
    }

    if (WFTState.Started.equals(wftSequenceState)
        && WorkflowExecutionUtils.isWorkflowTaskClosedEvent(event)) {
      if (event.getEventType().equals(EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED)) {
        workflowTaskCompletedEvent = Optional.of(event);
        wftSequenceState = WFTState.Closed;
      } else {
        wftSequenceState = WFTState.None;
      }
      addToBuffer(event);
      return;
    }

    if (WFTState.Closed.equals(wftSequenceState) && !WorkflowExecutionUtils.isCommandEvent(event)) {
      // this is the end of our WFT sequence.
      flushBuffer();
      // If event is WFT_STARTED or any of the Closing events, it's handled by if statements
      // earlier, so it's safe to switch to None here, we are not inside WFT sequence
      wftSequenceState = WFTState.None;

      addToBuffer(event);
      return;
    }
    if (WFTState.Closed.equals(wftSequenceState) && WorkflowExecutionUtils.isCommandEvent(event)) {
      // we are inside a closed WFT sequence, we can add to buffer
      addToBuffer(event);
      return;
    }

    if (WorkflowExecutionUtils.isCommandEvent(event)
        || WorkflowExecutionUtils.isWorkflowTaskClosedEvent(event)) {
      flushBuffer();
      readyToFetch.add(event);
    } else {
      addToBuffer(event);
    }
  }

  private void flushBuffer() {
    readyToFetch.addAll(wftBuffer);
    wftBuffer.clear();
  }

  private void addToBuffer(HistoryEvent event) {
    wftBuffer.add(event);
  }

  public EventBatch fetch() {
    if (readyToFetch.size() == 1) {
      HistoryEvent event = readyToFetch.get(0);
      Optional<HistoryEvent> wftCompleted = workflowTaskCompletedEvent;
      workflowTaskCompletedEvent = Optional.empty();
      readyToFetch.clear();
      return new EventBatch(wftCompleted, Collections.singletonList(event));
    } else {
      List<HistoryEvent> result = new ArrayList<>(readyToFetch);
      Optional<HistoryEvent> wftCompleted = workflowTaskCompletedEvent;
      workflowTaskCompletedEvent = Optional.empty();
      readyToFetch.clear();
      return new EventBatch(wftCompleted, result);
    }
  }
}
