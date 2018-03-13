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

package com.uber.cadence.internal.replay;

import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.worker.DecisionTaskWithHistoryIterator;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;

class HistoryHelper {

  static class EventsIterator implements Iterator<HistoryEvent> {

    private final DecisionTaskWithHistoryIterator decisionTaskWithHistoryIterator;

    private Iterator<HistoryEvent> events;

    private Queue<HistoryEvent> bufferedEvents = new ArrayDeque<>();
    private WorkflowExecutionStartedEventAttributes workflowExecutionStartedEventAttributes;

    public EventsIterator(DecisionTaskWithHistoryIterator decisionTaskWithHistoryIterator) {
      this.workflowExecutionStartedEventAttributes =
          decisionTaskWithHistoryIterator.getStartedEvent();
      this.decisionTaskWithHistoryIterator = decisionTaskWithHistoryIterator;
      this.events = decisionTaskWithHistoryIterator.getHistory();
    }

    @Override
    public boolean hasNext() {
      return !bufferedEvents.isEmpty() || events.hasNext();
    }

    @Override
    public HistoryEvent next() {
      if (bufferedEvents.isEmpty()) {
        return events.next();
      }
      return bufferedEvents.poll();
    }

    public PollForDecisionTaskResponse getDecisionTask() {
      return decisionTaskWithHistoryIterator.getDecisionTask();
    }

    public boolean isNextDecisionFailed() {
      while (events.hasNext()) {
        HistoryEvent event = events.next();
        bufferedEvents.add(event);
        EventType eventType = event.getEventType();
        if (eventType.equals(EventType.DecisionTaskTimedOut)) {
          return true;
        } else if (eventType.equals(EventType.DecisionTaskFailed)) {
          return true;
        } else if (eventType.equals(EventType.DecisionTaskCompleted)) {
          return false;
        }
      }
      return false;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    public WorkflowExecutionStartedEventAttributes getWorkflowExecutionStartedEventAttributes() {
      return workflowExecutionStartedEventAttributes;
    }
  }

  private final EventsIterator events;

  public HistoryHelper(DecisionTaskWithHistoryIterator decisionTasks) {
    this.events = new EventsIterator(decisionTasks);
  }

  public WorkflowExecutionStartedEventAttributes getWorkflowExecutionStartedEventAttributes() {
    return events.getWorkflowExecutionStartedEventAttributes();
  }

  public EventsIterator getEvents() {
    return events;
  }

  @Override
  public String toString() {
    return WorkflowExecutionUtils.prettyPrintHistory(
        events.getDecisionTask().getHistory().getEvents().iterator(), true);
  }

  public PollForDecisionTaskResponse getDecisionTask() {
    return events.getDecisionTask();
  }

  public long getLastNonReplayEventId() {
    return getDecisionTask().getPreviousStartedEventId();
  }
}
