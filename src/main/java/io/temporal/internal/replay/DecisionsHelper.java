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

import io.temporal.api.command.v1.CancelWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.FailWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.command.v1.RequestCancelExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.command.v1.UpsertWorkflowSearchAttributesCommandAttributes;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.ActivityTaskCancelRequestedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskCanceledEventAttributes;
import io.temporal.api.history.v1.ActivityTaskStartedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionTerminatedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.api.history.v1.ExternalWorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.api.history.v1.RequestCancelExternalWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.TimerCanceledEventAttributes;
import io.temporal.api.history.v1.TimerFiredEventAttributes;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.replay.HistoryHelper.DecisionEvents;
import io.temporal.internal.worker.WorkflowExecutionException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class DecisionsHelper {

  //  private static final Logger log = LoggerFactory.getLogger(DecisionsHelper.class);

  /**
   * TODO: Update constant once Temporal introduces the limit of decision per completion. Or remove
   * code path if Temporal deals with this problem differently like paginating through decisions.
   */
  private static final int MAXIMUM_DECISIONS_PER_COMPLETION = 10000;

  static final String FORCE_IMMEDIATE_DECISION_TIMER = "FORCE_IMMEDIATE_DECISION";

  private static final String NON_DETERMINISTIC_MESSAGE =
      "The possible causes are a nondeterministic workflow definition code or an incompatible "
          + "change in the workflow definition.";

  private final PollWorkflowTaskQueueResponse.Builder task;

  /**
   * When workflow task completes the decisions are converted to events that follow the decision
   * task completion event. The nextDecisionEventId is the id of an event that corresponds to the
   * next decision to be added.
   */
  private long nextDecisionEventId;

  private long lastStartedEventId;

  private long idCounter;

  private DecisionEvents decisionEvents;

  /** Use access-order to ensure that decisions are emitted in order of their creation */
  private final Map<DecisionId, DecisionStateMachine> decisions =
      new LinkedHashMap<>(100, 0.75f, true);

  // TODO: removal of completed activities
  private final Map<String, Long> activityIdToScheduledEventId = new HashMap<>();

  DecisionsHelper(PollWorkflowTaskQueueResponse.Builder task) {
    this.task = task;
  }

  long getNextDecisionEventId() {
    return nextDecisionEventId;
  }

  public long getLastStartedEventId() {
    return lastStartedEventId;
  }

  long scheduleActivityTask(ScheduleActivityTaskCommandAttributes schedule) {
    addAllMissingVersionMarker();

    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId = new DecisionId(DecisionTarget.ACTIVITY, nextDecisionEventId);
    activityIdToScheduledEventId.put(schedule.getActivityId(), nextDecisionEventId);
    addDecision(
        decisionId, new ActivityDecisionStateMachine(decisionId, schedule, nextDecisionEventId));
    return nextDecisionEventId;
  }

  /**
   * @return true if cancellation already happened as schedule event was found in the new decisions
   *     list
   */
  boolean requestCancelActivityTask(long scheduledEventId, Runnable immediateCancellationCallback) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, scheduledEventId));
    if (decision.cancel(immediateCancellationCallback)) {
      nextDecisionEventId++;
    }
    return decision.isDone();
  }

  void handleActivityTaskStarted(HistoryEvent event) {
    ActivityTaskStartedEventAttributes attributes = event.getActivityTaskStartedEventAttributes();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, attributes.getScheduledEventId()));
    decision.handleStartedEvent(event);
  }

  void handleActivityTaskScheduled(HistoryEvent event) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, event.getEventId()));
    decision.handleInitiatedEvent(event);
  }

  boolean handleActivityTaskClosed(long scheduledEventId) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, scheduledEventId));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleActivityTaskCancelRequested(HistoryEvent event) {
    ActivityTaskCancelRequestedEventAttributes attributes =
        event.getActivityTaskCancelRequestedEventAttributes();
    long scheduledEventId = attributes.getScheduledEventId();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, scheduledEventId));
    decision.handleCancellationInitiatedEvent();
    return decision.isDone();
  }

  private long getActivityScheduledEventId(String activityId) {
    Long scheduledEventId = activityIdToScheduledEventId.get(activityId);
    if (scheduledEventId == null) {
      throw new Error("Unknown activityId: " + activityId);
    }
    return scheduledEventId;
  }

  boolean handleActivityTaskCanceled(HistoryEvent event) {
    ActivityTaskCanceledEventAttributes attributes = event.getActivityTaskCanceledEventAttributes();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.ACTIVITY, attributes.getScheduledEventId()));
    decision.handleCancellationEvent();
    return decision.isDone();
  }

  long startChildWorkflowExecution(StartChildWorkflowExecutionCommandAttributes childWorkflow) {
    addAllMissingVersionMarker();

    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId = new DecisionId(DecisionTarget.CHILD_WORKFLOW, nextDecisionEventId);
    addDecision(decisionId, new ChildWorkflowDecisionStateMachine(decisionId, childWorkflow));
    return nextDecisionEventId;
  }

  void handleStartChildWorkflowExecutionInitiated(HistoryEvent event) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.CHILD_WORKFLOW, event.getEventId()));
    decision.handleInitiatedEvent(event);
  }

  boolean handleStartChildWorkflowExecutionFailed(HistoryEvent event) {
    StartChildWorkflowExecutionFailedEventAttributes attributes =
        event.getStartChildWorkflowExecutionFailedEventAttributes();
    long initiatedEventId = attributes.getInitiatedEventId();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.CHILD_WORKFLOW, initiatedEventId));
    decision.handleInitiationFailedEvent(event);
    return decision.isDone();
  }

  /**
   * @return true if cancellation already happened as schedule event was found in the new decisions
   *     list
   */
  long requestCancelExternalWorkflowExecution(
      RequestCancelExternalWorkflowExecutionCommandAttributes schedule) {
    addAllMissingVersionMarker();

    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId =
        new DecisionId(DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, nextDecisionEventId);
    addDecision(
        decisionId, new ExternalWorkflowCancellationDecisionStateMachine(decisionId, schedule));
    return nextDecisionEventId;
  }

  void handleRequestCancelExternalWorkflowExecutionInitiated(HistoryEvent event) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, event.getEventId()));
    decision.handleInitiatedEvent(event);
  }

  void handleExternalWorkflowExecutionCancelRequested(HistoryEvent event) {
    ExternalWorkflowExecutionCancelRequestedEventAttributes attributes =
        event.getExternalWorkflowExecutionCancelRequestedEventAttributes();
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(
                DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
  }

  void handleRequestCancelExternalWorkflowExecutionFailed(HistoryEvent event) {
    RequestCancelExternalWorkflowExecutionFailedEventAttributes attributes =
        event.getRequestCancelExternalWorkflowExecutionFailedEventAttributes();
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(
                DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
  }

  long signalExternalWorkflowExecution(SignalExternalWorkflowExecutionCommandAttributes signal) {
    addAllMissingVersionMarker();

    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId =
        new DecisionId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, nextDecisionEventId);
    addDecision(decisionId, new SignalDecisionStateMachine(decisionId, signal));
    return nextDecisionEventId;
  }

  void cancelSignalExternalWorkflowExecution(
      long initiatedEventId, Runnable immediateCancellationCallback) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, initiatedEventId));
    if (decision.cancel(immediateCancellationCallback)) {
      nextDecisionEventId++;
    }
  }

  boolean handleSignalExternalWorkflowExecutionFailed(long initiatedEventId) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, initiatedEventId));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleExternalWorkflowExecutionSignaled(long initiatedEventId) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, initiatedEventId));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  long startTimer(StartTimerCommandAttributes request) {
    addAllMissingVersionMarker();

    long startEventId = getNextDecisionEventId();
    DecisionId decisionId = new DecisionId(DecisionTarget.TIMER, startEventId);
    addDecision(decisionId, new TimerDecisionStateMachine(decisionId, request));
    return startEventId;
  }

  boolean cancelTimer(long startEventId, Runnable immediateCancellationCallback) {
    DecisionStateMachine decision = getDecision(new DecisionId(DecisionTarget.TIMER, startEventId));
    if (decision.isDone()) {
      // Cancellation callbacks are not deregistered and might be invoked after timer firing
      return true;
    }
    if (decision.cancel(immediateCancellationCallback)) {
      nextDecisionEventId++;
    }
    return decision.isDone();
  }

  void handleChildWorkflowExecutionStarted(HistoryEvent event) {
    ChildWorkflowExecutionStartedEventAttributes attributes =
        event.getChildWorkflowExecutionStartedEventAttributes();
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleStartedEvent(event);
  }

  boolean handleChildWorkflowExecutionCompleted(
      ChildWorkflowExecutionCompletedEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleChildWorkflowExecutionTimedOut(
      ChildWorkflowExecutionTimedOutEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleChildWorkflowExecutionTerminated(
      ChildWorkflowExecutionTerminatedEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleChildWorkflowExecutionFailed(
      ChildWorkflowExecutionFailedEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleChildWorkflowExecutionCanceled(
      ChildWorkflowExecutionCanceledEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(
            new DecisionId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    decision.handleCancellationEvent();
    return decision.isDone();
  }

  void handleSignalExternalWorkflowExecutionInitiated(HistoryEvent event) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, event.getEventId()));
    decision.handleInitiatedEvent(event);
  }

  boolean handleTimerClosed(TimerFiredEventAttributes attributes) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.TIMER, attributes.getStartedEventId()));
    decision.handleCompletionEvent();
    return decision.isDone();
  }

  boolean handleTimerCanceled(HistoryEvent event) {
    TimerCanceledEventAttributes attributes = event.getTimerCanceledEventAttributes();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.TIMER, attributes.getStartedEventId()));
    decision.handleCancellationEvent();
    return decision.isDone();
  }

  boolean handleCancelTimerFailed(HistoryEvent event) {
    long startedEventId = event.getEventId();
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.TIMER, startedEventId));
    decision.handleCancellationFailureEvent(event);
    return decision.isDone();
  }

  void handleTimerStarted(HistoryEvent event) {
    DecisionStateMachine decision =
        getDecision(new DecisionId(DecisionTarget.TIMER, event.getEventId()));
    // Timer started event is indeed initiation event for the timer as
    // it doesn't have a separate event for started as an activity does.
    decision.handleInitiatedEvent(event);
  }

  /** This happens during strongly consistent query processing for completed workflows */
  public void handleWorkflowExecutionCompleted(HistoryEvent event) {
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    DecisionStateMachine decision = getDecision(decisionId);
    if (!(decision instanceof CompleteWorkflowStateMachine)) {
      throw new IllegalStateException("Unexpected decision: " + decision);
    }
    decisions.clear();
  }

  void completeWorkflowExecution(Optional<Payloads> output) {
    addAllMissingVersionMarker();

    CompleteWorkflowExecutionCommandAttributes.Builder attributes =
        CompleteWorkflowExecutionCommandAttributes.newBuilder();
    if (output.isPresent()) {
      attributes.setResult(output.get());
    }
    Command decision =
        Command.newBuilder()
            .setCompleteWorkflowExecutionCommandAttributes(attributes)
            .setCommandType(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION)
            .build();
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  void continueAsNewWorkflowExecution(ContinueAsNewWorkflowExecutionCommandAttributes attributes) {
    addAllMissingVersionMarker();

    HistoryEvent firstEvent = task.getHistory().getEvents(0);
    if (!firstEvent.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalStateException(
          "The first event is not WorkflowExecutionStarted: " + firstEvent);
    }

    Command decision =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION)
            .setContinueAsNewWorkflowExecutionCommandAttributes(attributes)
            .build();

    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  void failWorkflowExecution(WorkflowExecutionException exception) {
    addAllMissingVersionMarker();

    FailWorkflowExecutionCommandAttributes.Builder attributes =
        FailWorkflowExecutionCommandAttributes.newBuilder().setFailure(exception.getFailure());
    Command decision =
        Command.newBuilder()
            .setFailWorkflowExecutionCommandAttributes(attributes)
            .setCommandType(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION)
            .build();
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  /**
   * @return <code>false</code> means that cancel failed, <code>true</code> that
   *     CancelWorkflowExecution was created.
   */
  void cancelWorkflowExecution() {
    addAllMissingVersionMarker();

    Command decision =
        Command.newBuilder()
            .setCancelWorkflowExecutionCommandAttributes(
                CancelWorkflowExecutionCommandAttributes.getDefaultInstance())
            .setCommandType(CommandType.COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION)
            .build();
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  void recordMarker(
      String markerName,
      Optional<Header> header,
      Map<String, Payloads> details,
      Optional<Failure> failure) {
    // no need to call addAllMissingVersionMarker here as all the callers are already doing it.

    RecordMarkerCommandAttributes.Builder marker =
        RecordMarkerCommandAttributes.newBuilder().setMarkerName(markerName);
    marker.putAllDetails(details);
    if (header.isPresent()) {
      marker.setHeader(header.get());
    }
    if (failure.isPresent()) {
      marker.setFailure(failure.get());
    }
    Command decision =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
            .setRecordMarkerCommandAttributes(marker)
            .build();
    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId = new DecisionId(DecisionTarget.MARKER, nextDecisionEventId);
    addDecision(decisionId, new MarkerDecisionStateMachine(decisionId, decision));
  }

  void upsertSearchAttributes(SearchAttributes searchAttributes) {
    Command decision =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES)
            .setUpsertWorkflowSearchAttributesCommandAttributes(
                UpsertWorkflowSearchAttributesCommandAttributes.newBuilder()
                    .setSearchAttributes(searchAttributes))
            .build();
    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId =
        new DecisionId(DecisionTarget.UPSERT_SEARCH_ATTRIBUTES, nextDecisionEventId);
    addDecision(decisionId, new UpsertSearchAttributesDecisionStateMachine(decisionId, decision));
  }

  List<Command> getDecisions() {
    List<Command> result = new ArrayList<>(MAXIMUM_DECISIONS_PER_COMPLETION + 1);
    for (DecisionStateMachine decisionStateMachine : decisions.values()) {
      Command decision = decisionStateMachine.getDecision();
      if (decision != null) {
        result.add(decision);
      }
    }
    // Include FORCE_IMMEDIATE_DECISION timer only if there are more then 100 events
    int size = result.size();
    if (size > MAXIMUM_DECISIONS_PER_COMPLETION
        && !isCompletionEvent(result.get(MAXIMUM_DECISIONS_PER_COMPLETION - 2))) {
      result = result.subList(0, MAXIMUM_DECISIONS_PER_COMPLETION - 1);
      Command d =
          Command.newBuilder()
              .setStartTimerCommandAttributes(
                  StartTimerCommandAttributes.newBuilder()
                      .setStartToFireTimeoutSeconds(0)
                      .setTimerId(FORCE_IMMEDIATE_DECISION_TIMER))
              .setCommandType(CommandType.COMMAND_TYPE_START_TIMER)
              .build();
      result.add(d);
    }

    return result;
  }

  private boolean isCompletionEvent(Command decision) {
    CommandType type = decision.getCommandType();
    switch (type) {
      case COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION:
      case COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION:
        return true;
      default:
        return false;
    }
  }

  public void handleWorkflowTaskStartedEvent(DecisionEvents decision) {
    this.decisionEvents = decision;
    this.nextDecisionEventId = decision.getNextDecisionEventId();
    // Account for DecisionCompleted
    this.lastStartedEventId = decision.getNextDecisionEventId() - 2;
  }

  void notifyDecisionSent() {
    int count = 0;
    Iterator<DecisionStateMachine> iterator = decisions.values().iterator();
    DecisionStateMachine next = null;

    DecisionStateMachine decisionStateMachine = getNextDecision(iterator);
    while (decisionStateMachine != null) {
      next = getNextDecision(iterator);
      if (++count == MAXIMUM_DECISIONS_PER_COMPLETION
          && next != null
          && !isCompletionEvent(next.getDecision())) {
        break;
      }
      decisionStateMachine.handleWorkflowTaskStartedEvent();
      decisionStateMachine = next;
    }
    if (next != null && count < MAXIMUM_DECISIONS_PER_COMPLETION) {
      next.handleWorkflowTaskStartedEvent();
    }
  }

  private DecisionStateMachine getNextDecision(Iterator<DecisionStateMachine> iterator) {
    DecisionStateMachine result = null;
    while (result == null && iterator.hasNext()) {
      result = iterator.next();
      if (result.getDecision() == null) {
        result = null;
      }
    }
    return result;
  }

  @Override
  public String toString() {
    return WorkflowExecutionUtils.prettyPrintDecisions(getDecisions());
  }

  PollWorkflowTaskQueueResponse.Builder getTask() {
    return task;
  }

  // addAllMissingVersionMarker should always be called before addDecision. In non-replay mode,
  // addAllMissingVersionMarker is a no-op. In replay mode, it tries to insert back missing
  // version marker decisions, as we allow user to remove getVersion and not breaking their code.
  // Be careful that addAllMissingVersionMarker can add decision and hence change
  // nextDecisionEventId, so any call to determine the event ID for the next decision should happen
  // after that.
  private void addDecision(DecisionId decisionId, DecisionStateMachine decision) {
    Objects.requireNonNull(decisionId);
    decisions.put(decisionId, decision);
    nextDecisionEventId++;
  }

  void addAllMissingVersionMarker() {
    addAllMissingVersionMarker(Optional.empty(), Optional.empty());
  }

  Optional<HistoryEvent> getVersionMakerEvent(long eventId) {
    Optional<HistoryEvent> optionalEvent = getOptionalDecisionEvent(eventId);
    if (!optionalEvent.isPresent()) {
      return Optional.empty();
    }

    HistoryEvent event = optionalEvent.get();
    if (event.getEventType() != EventType.EVENT_TYPE_MARKER_RECORDED) {
      return Optional.empty();
    }

    if (!event
        .getMarkerRecordedEventAttributes()
        .getMarkerName()
        .equals(ClockDecisionContext.VERSION_MARKER_NAME)) {
      return Optional.empty();
    }
    return Optional.of(event);
  }

  /**
   * As getVersion calls can be added and removed any time this method inserts missing decision
   * events that correspond to removed getVersion calls.
   *
   * @param changeId optional getVersion change id to compare
   * @param converter must be present if changeId is present
   */
  void addAllMissingVersionMarker(Optional<String> changeId, Optional<DataConverter> converter) {
    Optional<HistoryEvent> markerEvent = getVersionMakerEvent(nextDecisionEventId);

    if (!markerEvent.isPresent()) {
      return;
    }

    // Look ahead to see if there is a marker with changeId following current version marker
    // If it is the case then all the markers that precede it should be added as decisions
    // as their correspondent getVersion calls were removed.
    long changeIdMarkerEventId = -1;
    if (changeId.isPresent()) {
      String id = changeId.get();
      long eventId = nextDecisionEventId;
      while (true) {
        MarkerRecordedEventAttributes eventAttributes =
            markerEvent.get().getMarkerRecordedEventAttributes();
        MarkerHandler.MarkerData markerData =
            MarkerHandler.MarkerData.fromEventAttributes(eventAttributes, converter.get());

        if (id.equals(markerData.getId())) {
          changeIdMarkerEventId = eventId;
          break;
        }
        eventId++;
        markerEvent = getVersionMakerEvent(eventId);
        if (!markerEvent.isPresent()) {
          break;
        }
      }
      // There are no version markers preceding a marker with the changeId
      if (changeIdMarkerEventId < 0 || changeIdMarkerEventId == nextDecisionEventId) {
        return;
      }
    }
    do {
      MarkerRecordedEventAttributes eventAttributes =
          markerEvent.get().getMarkerRecordedEventAttributes();
      // If we have a version marker in history event but not in decisions, let's add one.
      RecordMarkerCommandAttributes.Builder attributes =
          RecordMarkerCommandAttributes.newBuilder()
              .setMarkerName(ClockDecisionContext.VERSION_MARKER_NAME);
      if (eventAttributes.hasHeader()) {
        attributes.setHeader(eventAttributes.getHeader());
      }
      if (eventAttributes.hasFailure()) {
        attributes.setFailure(eventAttributes.getFailure());
      }
      attributes.putAllDetails(eventAttributes.getDetailsMap());
      Command markerDecision =
          Command.newBuilder()
              .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
              .setRecordMarkerCommandAttributes(attributes)
              .build();
      DecisionId markerDecisionId = new DecisionId(DecisionTarget.MARKER, nextDecisionEventId);
      decisions.put(
          markerDecisionId, new MarkerDecisionStateMachine(markerDecisionId, markerDecision));
      nextDecisionEventId++;
      markerEvent = getVersionMakerEvent(nextDecisionEventId);
    } while (markerEvent.isPresent()
        && (changeIdMarkerEventId < 0 || nextDecisionEventId < changeIdMarkerEventId));
  }

  private DecisionStateMachine getDecision(DecisionId decisionId) {
    DecisionStateMachine result = decisions.get(decisionId);
    if (result == null) {
      throw new NonDeterminisicWorkflowError(
          "Unknown " + decisionId + ". " + NON_DETERMINISTIC_MESSAGE);
    }
    return result;
  }

  String getAndIncrementNextId() {
    return String.valueOf(idCounter++);
  }

  Optional<HistoryEvent> getOptionalDecisionEvent(long eventId) {
    return decisionEvents.getOptionalDecisionEvent(eventId);
  }
}
