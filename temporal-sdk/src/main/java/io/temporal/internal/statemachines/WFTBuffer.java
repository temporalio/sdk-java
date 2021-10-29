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

import com.google.common.base.Preconditions;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.internal.common.WorkflowExecutionUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

  private WFTState wftSequenceState = WFTState.None;

  private final List<HistoryEvent> wftBuffer = new ArrayList<>();
  private final List<HistoryEvent> readyToFetch = new ArrayList<>();

  /**
   * @return Should the buffer be fetched. true if a whole history for a workflow task is
   *     accumulated or events can't be attributed to a completed workflow task
   */
  public boolean addEvent(HistoryEvent event, boolean hasNextEvent) {
    handleEvent(event, hasNextEvent);
    return !readyToFetch.isEmpty();
  }

  private void handleEvent(HistoryEvent event, boolean hasNextEvent) {
    if (!hasNextEvent) {
      // flush buffer
      flushBuffer();

      // exit the sequence
      wftSequenceState = WFTState.None;
      readyToFetch.add(event);
      return;
    }

    // This is the only way to enter into the WFT sequence -
    // any WorkflowTaskStarted event that it not the last in the history
    if (EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED.equals(event.getEventType())) {
      // if there is something in wftBuffer, let's flush it
      flushBuffer();
      // and init a new sequence
      wftSequenceState = WFTState.Started;
      addToBuffer(event);
      return;
    }

    if (WFTState.Started.equals(wftSequenceState)
        && WorkflowExecutionUtils.isWorkflowTaskClosedEvent(event)) {
      wftSequenceState = WFTState.Closed;
      addToBuffer(event);
      return;
    }

    if (WFTState.Closed.equals(wftSequenceState) && !WorkflowExecutionUtils.isCommandEvent(event)) {
      // this is the end of our WFT sequence.
      flushBuffer();
      // If event is WFT_STARTED or any of the Closing events, it's handled by if statements
      // earlier, so it's safe to switch to None here, we are not inside WFT sequence
      wftSequenceState = WFTState.None;
      readyToFetch.add(event);
      return;
    }

    if (WFTState.None.equals(wftSequenceState)) {
      // we should be returning the events one by one, we are not inside a WFT sequence
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
    Preconditions.checkState(
        !WFTState.None.equals(wftSequenceState),
        "We should be inside an open WFT sequence to add to the buffer");
    wftBuffer.add(event);
  }

  public List<HistoryEvent> fetch() {
    if (readyToFetch.size() == 1) {
      HistoryEvent event = readyToFetch.get(0);
      readyToFetch.clear();
      return Collections.singletonList(event);
    } else {
      List<HistoryEvent> result = new ArrayList<>(readyToFetch);
      readyToFetch.clear();
      return result;
    }
  }
}
