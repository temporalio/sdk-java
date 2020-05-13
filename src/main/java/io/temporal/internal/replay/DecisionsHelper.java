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

import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.WorkflowExecutionUtils;
import io.temporal.internal.replay.HistoryHelper.DecisionEvents;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.proto.common.Header;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.SearchAttributes;
import io.temporal.proto.common.WorkflowType;
import io.temporal.proto.decision.CancelWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.CompleteWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.ContinueAsNewWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.Decision;
import io.temporal.proto.decision.DecisionType;
import io.temporal.proto.decision.FailWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.RecordMarkerDecisionAttributes;
import io.temporal.proto.decision.RequestCancelExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.ScheduleActivityTaskDecisionAttributes;
import io.temporal.proto.decision.SignalExternalWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.StartChildWorkflowExecutionDecisionAttributes;
import io.temporal.proto.decision.StartTimerDecisionAttributes;
import io.temporal.proto.decision.UpsertWorkflowSearchAttributesDecisionAttributes;
import io.temporal.proto.event.ActivityTaskCancelRequestedEventAttributes;
import io.temporal.proto.event.ActivityTaskCanceledEventAttributes;
import io.temporal.proto.event.ActivityTaskStartedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionTerminatedEventAttributes;
import io.temporal.proto.event.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.proto.event.EventType;
import io.temporal.proto.event.ExternalWorkflowExecutionCancelRequestedEventAttributes;
import io.temporal.proto.event.HistoryEvent;
import io.temporal.proto.event.RequestCancelExternalWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.event.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.proto.event.TimerCanceledEventAttributes;
import io.temporal.proto.event.TimerFiredEventAttributes;
import io.temporal.proto.event.WorkflowExecutionStartedEventAttributes;
import io.temporal.proto.tasklist.TaskList;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

class DecisionsHelper {

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

  private final PollForDecisionTaskResponse.Builder task;

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

  DecisionsHelper(PollForDecisionTaskResponse.Builder task) {
    this.task = task;
  }

  long getNextDecisionEventId() {
    return nextDecisionEventId;
  }

  public long getLastStartedEventId() {
    return lastStartedEventId;
  }

  long scheduleActivityTask(ScheduleActivityTaskDecisionAttributes schedule) {
    addAllMissingVersionMarker();

    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId = new DecisionId(DecisionTarget.ACTIVITY, nextDecisionEventId);
    activityIdToScheduledEventId.put(schedule.getActivityId(), nextDecisionEventId);
    addDecision(decisionId, new ActivityDecisionStateMachine(decisionId, schedule));
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

  long startChildWorkflowExecution(StartChildWorkflowExecutionDecisionAttributes childWorkflow) {
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
      RequestCancelExternalWorkflowExecutionDecisionAttributes schedule) {
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

  long signalExternalWorkflowExecution(SignalExternalWorkflowExecutionDecisionAttributes signal) {
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

  long startTimer(StartTimerDecisionAttributes request) {
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

    CompleteWorkflowExecutionDecisionAttributes.Builder attributes =
        CompleteWorkflowExecutionDecisionAttributes.newBuilder();
    if (output.isPresent()) {
      attributes.setResult(output.get());
    }
    Decision decision =
        Decision.newBuilder()
            .setCompleteWorkflowExecutionDecisionAttributes(attributes)
            .setDecisionType(DecisionType.CompleteWorkflowExecution)
            .build();
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  void continueAsNewWorkflowExecution(ContinueAsNewWorkflowExecutionParameters continueParameters) {
    addAllMissingVersionMarker();

    HistoryEvent firstEvent = task.getHistory().getEvents(0);
    if (!firstEvent.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalStateException(
          "The first event is not WorkflowExecutionStarted: " + firstEvent);
    }
    WorkflowExecutionStartedEventAttributes startedEvent =
        firstEvent.getWorkflowExecutionStartedEventAttributes();
    ContinueAsNewWorkflowExecutionDecisionAttributes.Builder attributes =
        ContinueAsNewWorkflowExecutionDecisionAttributes.newBuilder();
    attributes.setInput(continueParameters.getInput());
    String workflowType = continueParameters.getWorkflowType();
    if (workflowType != null && !workflowType.isEmpty()) {
      attributes.setWorkflowType(WorkflowType.newBuilder().setName(workflowType));
    } else {
      attributes.setWorkflowType(task.getWorkflowType());
    }
    int executionStartToClose = continueParameters.getWorkflowRunTimeoutSeconds();
    if (executionStartToClose == 0) {
      executionStartToClose = startedEvent.getWorkflowRunTimeoutSeconds();
    }
    attributes.setWorkflowRunTimeoutSeconds(executionStartToClose);
    int taskStartToClose = continueParameters.getWorkflowTaskTimeoutSeconds();
    if (taskStartToClose == 0) {
      taskStartToClose = startedEvent.getWorkflowTaskTimeoutSeconds();
    }
    attributes.setWorkflowTaskTimeoutSeconds(taskStartToClose);
    String taskList = continueParameters.getTaskList();
    if (taskList == null || taskList.isEmpty()) {
      taskList = startedEvent.getTaskList().getName();
    }
    attributes.setTaskList(TaskList.newBuilder().setName(taskList).build());

    // TODO(maxim): Find out what to do about memo, searchAttributes and header

    Decision decision =
        Decision.newBuilder()
            .setDecisionType(DecisionType.ContinueAsNewWorkflowExecution)
            .setContinueAsNewWorkflowExecutionDecisionAttributes(attributes)
            .build();

    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  void failWorkflowExecution(WorkflowExecutionException failure) {
    addAllMissingVersionMarker();

    FailWorkflowExecutionDecisionAttributes.Builder attributes =
        FailWorkflowExecutionDecisionAttributes.newBuilder().setReason(failure.getReason());
    Optional<Payloads> details = failure.getDetails();
    if (details.isPresent()) {
      attributes.setDetails(details.get());
    }
    Decision decision =
        Decision.newBuilder()
            .setFailWorkflowExecutionDecisionAttributes(attributes)
            .setDecisionType(DecisionType.FailWorkflowExecution)
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

    Decision decision =
        Decision.newBuilder()
            .setCancelWorkflowExecutionDecisionAttributes(
                CancelWorkflowExecutionDecisionAttributes.getDefaultInstance())
            .setDecisionType(DecisionType.CancelWorkflowExecution)
            .build();
    DecisionId decisionId = new DecisionId(DecisionTarget.SELF, 0);
    addDecision(decisionId, new CompleteWorkflowStateMachine(decisionId, decision));
  }

  void recordMarker(String markerName, Header header, Optional<Payloads> details) {
    // no need to call addAllMissingVersionMarker here as all the callers are already doing it.

    RecordMarkerDecisionAttributes.Builder marker =
        RecordMarkerDecisionAttributes.newBuilder().setMarkerName(markerName);
    if (details.isPresent()) {
      marker.setDetails(details.get());
    }
    if (header != null) {
      marker.setHeader(header);
    }
    Decision decision =
        Decision.newBuilder()
            .setDecisionType(DecisionType.RecordMarker)
            .setRecordMarkerDecisionAttributes(marker)
            .build();
    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId = new DecisionId(DecisionTarget.MARKER, nextDecisionEventId);
    addDecision(decisionId, new MarkerDecisionStateMachine(decisionId, decision));
  }

  void upsertSearchAttributes(SearchAttributes searchAttributes) {
    Decision decision =
        Decision.newBuilder()
            .setDecisionType(DecisionType.UpsertWorkflowSearchAttributes)
            .setUpsertWorkflowSearchAttributesDecisionAttributes(
                UpsertWorkflowSearchAttributesDecisionAttributes.newBuilder()
                    .setSearchAttributes(searchAttributes))
            .build();
    long nextDecisionEventId = getNextDecisionEventId();
    DecisionId decisionId =
        new DecisionId(DecisionTarget.UPSERT_SEARCH_ATTRIBUTES, nextDecisionEventId);
    addDecision(decisionId, new UpsertSearchAttributesDecisionStateMachine(decisionId, decision));
  }

  List<Decision> getDecisions() {
    List<Decision> result = new ArrayList<>(MAXIMUM_DECISIONS_PER_COMPLETION + 1);
    for (DecisionStateMachine decisionStateMachine : decisions.values()) {
      Decision decision = decisionStateMachine.getDecision();
      if (decision != null) {
        result.add(decision);
      }
    }
    // Include FORCE_IMMEDIATE_DECISION timer only if there are more then 100 events
    int size = result.size();
    if (size > MAXIMUM_DECISIONS_PER_COMPLETION
        && !isCompletionEvent(result.get(MAXIMUM_DECISIONS_PER_COMPLETION - 2))) {
      result = result.subList(0, MAXIMUM_DECISIONS_PER_COMPLETION - 1);
      Decision d =
          Decision.newBuilder()
              .setStartTimerDecisionAttributes(
                  StartTimerDecisionAttributes.newBuilder()
                      .setStartToFireTimeoutSeconds(0)
                      .setTimerId(FORCE_IMMEDIATE_DECISION_TIMER))
              .setDecisionType(DecisionType.StartTimer)
              .build();
      result.add(d);
    }

    return result;
  }

  private boolean isCompletionEvent(Decision decision) {
    DecisionType type = decision.getDecisionType();
    switch (type) {
      case CancelWorkflowExecution:
      case CompleteWorkflowExecution:
      case FailWorkflowExecution:
      case ContinueAsNewWorkflowExecution:
        return true;
      default:
        return false;
    }
  }

  public void handleDecisionTaskStartedEvent(DecisionEvents decision) {
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
      decisionStateMachine.handleDecisionTaskStartedEvent();
      decisionStateMachine = next;
    }
    if (next != null && count < MAXIMUM_DECISIONS_PER_COMPLETION) {
      next.handleDecisionTaskStartedEvent();
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

  PollForDecisionTaskResponse.Builder getTask() {
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
    if (event.getEventType() != EventType.MarkerRecorded) {
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
        MarkerHandler.MarkerInterface markerData =
            MarkerHandler.MarkerInterface.fromEventAttributes(
                markerEvent.get().getMarkerRecordedEventAttributes(), converter.get());

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
      // If we have a version marker in history event but not in decisions, let's add one.
      RecordMarkerDecisionAttributes.Builder attributes =
          RecordMarkerDecisionAttributes.newBuilder()
              .setMarkerName(ClockDecisionContext.VERSION_MARKER_NAME)
              .setHeader(markerEvent.get().getMarkerRecordedEventAttributes().getHeader())
              .setDetails(markerEvent.get().getMarkerRecordedEventAttributes().getDetails());
      Decision markerDecision =
          Decision.newBuilder()
              .setDecisionType(DecisionType.RecordMarker)
              .setRecordMarkerDecisionAttributes(attributes)
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
