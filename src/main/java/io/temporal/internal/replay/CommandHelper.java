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

final class CommandHelper {

  //  private static final Logger log = LoggerFactory.getLogger(DecisionsHelper.class);

  /**
   * TODO: Update constant once Temporal introduces the limit of command per completion. Or remove
   * code path if Temporal deals with this problem differently like paginating through decisions.
   */
  private static final int MAXIMUM_DECISIONS_PER_COMPLETION = 10000;

  static final String FORCE_IMMEDIATE_DECISION_TIMER = "FORCE_IMMEDIATE_DECISION";

  private static final String NON_DETERMINISTIC_MESSAGE =
      "The possible causes are a nondeterministic workflow definition code or an incompatible "
          + "change in the workflow definition.";

  private final PollWorkflowTaskQueueResponse.Builder task;

  /**
   * When workflow task completes the decisions are converted to events that follow the command task
   * completion event. The nextDecisionEventId is the id of an event that corresponds to the next
   * command to be added.
   */
  private long nextDecisionEventId;

  private long lastStartedEventId;

  private long idCounter;

  private DecisionEvents decisionEvents;

  /** Use access-order to ensure that decisions are emitted in order of their creation */
  private final Map<CommandId, CommandStateMachine> decisions =
      new LinkedHashMap<>(100, 0.75f, true);

  // TODO: removal of completed activities
  private final Map<String, Long> activityIdToScheduledEventId = new HashMap<>();

  CommandHelper(PollWorkflowTaskQueueResponse.Builder task) {
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
    CommandId commandId = new CommandId(DecisionTarget.ACTIVITY, nextDecisionEventId);
    activityIdToScheduledEventId.put(schedule.getActivityId(), nextDecisionEventId);
    addDecision(
        commandId, new ActivityCommandStateMachine(commandId, schedule, nextDecisionEventId));
    return nextDecisionEventId;
  }

  /**
   * @return true if cancellation already happened as schedule event was found in the new decisions
   *     list
   */
  boolean requestCancelActivityTask(long scheduledEventId, Runnable immediateCancellationCallback) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.ACTIVITY, scheduledEventId));
    if (command.cancel(immediateCancellationCallback)) {
      nextDecisionEventId++;
    }
    return command.isDone();
  }

  void handleActivityTaskStarted(HistoryEvent event) {
    ActivityTaskStartedEventAttributes attributes = event.getActivityTaskStartedEventAttributes();
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.ACTIVITY, attributes.getScheduledEventId()));
    command.handleStartedEvent(event);
  }

  void handleActivityTaskScheduled(HistoryEvent event) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.ACTIVITY, event.getEventId()));
    command.handleInitiatedEvent(event);
  }

  boolean handleActivityTaskClosed(long scheduledEventId) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.ACTIVITY, scheduledEventId));
    command.handleCompletionEvent();
    return command.isDone();
  }

  boolean handleActivityTaskCancelRequested(HistoryEvent event) {
    ActivityTaskCancelRequestedEventAttributes attributes =
        event.getActivityTaskCancelRequestedEventAttributes();
    long scheduledEventId = attributes.getScheduledEventId();
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.ACTIVITY, scheduledEventId));
    command.handleCancellationInitiatedEvent();
    return command.isDone();
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
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.ACTIVITY, attributes.getScheduledEventId()));
    command.handleCancellationEvent();
    return command.isDone();
  }

  long startChildWorkflowExecution(StartChildWorkflowExecutionCommandAttributes childWorkflow) {
    addAllMissingVersionMarker();

    long nextDecisionEventId = getNextDecisionEventId();
    CommandId commandId = new CommandId(DecisionTarget.CHILD_WORKFLOW, nextDecisionEventId);
    addDecision(commandId, new ChildWorkflowCommandStateMachine(commandId, childWorkflow));
    return nextDecisionEventId;
  }

  void handleStartChildWorkflowExecutionInitiated(HistoryEvent event) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.CHILD_WORKFLOW, event.getEventId()));
    command.handleInitiatedEvent(event);
  }

  boolean handleStartChildWorkflowExecutionFailed(HistoryEvent event) {
    StartChildWorkflowExecutionFailedEventAttributes attributes =
        event.getStartChildWorkflowExecutionFailedEventAttributes();
    long initiatedEventId = attributes.getInitiatedEventId();
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.CHILD_WORKFLOW, initiatedEventId));
    command.handleInitiationFailedEvent(event);
    return command.isDone();
  }

  /**
   * @return true if cancellation already happened as schedule event was found in the new decisions
   *     list
   */
  long requestCancelExternalWorkflowExecution(
      RequestCancelExternalWorkflowExecutionCommandAttributes schedule) {
    addAllMissingVersionMarker();

    long nextDecisionEventId = getNextDecisionEventId();
    CommandId commandId =
        new CommandId(DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, nextDecisionEventId);
    addDecision(
        commandId, new ExternalWorkflowCancellationCommandStateMachine(commandId, schedule));
    return nextDecisionEventId;
  }

  void handleRequestCancelExternalWorkflowExecutionInitiated(HistoryEvent event) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, event.getEventId()));
    command.handleInitiatedEvent(event);
  }

  void handleExternalWorkflowExecutionCancelRequested(HistoryEvent event) {
    ExternalWorkflowExecutionCancelRequestedEventAttributes attributes =
        event.getExternalWorkflowExecutionCancelRequestedEventAttributes();
    CommandStateMachine command =
        getDecision(
            new CommandId(
                DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, attributes.getInitiatedEventId()));
    command.handleCompletionEvent();
  }

  void handleRequestCancelExternalWorkflowExecutionFailed(HistoryEvent event) {
    RequestCancelExternalWorkflowExecutionFailedEventAttributes attributes =
        event.getRequestCancelExternalWorkflowExecutionFailedEventAttributes();
    CommandStateMachine command =
        getDecision(
            new CommandId(
                DecisionTarget.CANCEL_EXTERNAL_WORKFLOW, attributes.getInitiatedEventId()));
    command.handleCompletionEvent();
  }

  long signalExternalWorkflowExecution(SignalExternalWorkflowExecutionCommandAttributes signal) {
    addAllMissingVersionMarker();

    long nextDecisionEventId = getNextDecisionEventId();
    CommandId commandId =
        new CommandId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, nextDecisionEventId);
    addDecision(commandId, new SignalCommandStateMachine(commandId, signal));
    return nextDecisionEventId;
  }

  void cancelSignalExternalWorkflowExecution(
      long initiatedEventId, Runnable immediateCancellationCallback) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, initiatedEventId));
    if (command.cancel(immediateCancellationCallback)) {
      nextDecisionEventId++;
    }
  }

  boolean handleSignalExternalWorkflowExecutionFailed(long initiatedEventId) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, initiatedEventId));
    command.handleCompletionEvent();
    return command.isDone();
  }

  boolean handleExternalWorkflowExecutionSignaled(long initiatedEventId) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, initiatedEventId));
    command.handleCompletionEvent();
    return command.isDone();
  }

  long startTimer(StartTimerCommandAttributes request) {
    addAllMissingVersionMarker();

    long startEventId = getNextDecisionEventId();
    CommandId commandId = new CommandId(DecisionTarget.TIMER, startEventId);
    addDecision(commandId, new TimerCommandStateMachine(commandId, request));
    return startEventId;
  }

  boolean cancelTimer(long startEventId, Runnable immediateCancellationCallback) {
    CommandStateMachine command = getDecision(new CommandId(DecisionTarget.TIMER, startEventId));
    if (command.isDone()) {
      // Cancellation callbacks are not deregistered and might be invoked after timer firing
      return true;
    }
    if (command.cancel(immediateCancellationCallback)) {
      nextDecisionEventId++;
    }
    return command.isDone();
  }

  void handleChildWorkflowExecutionStarted(HistoryEvent event) {
    ChildWorkflowExecutionStartedEventAttributes attributes =
        event.getChildWorkflowExecutionStartedEventAttributes();
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    command.handleStartedEvent(event);
  }

  boolean handleChildWorkflowExecutionCompleted(
      ChildWorkflowExecutionCompletedEventAttributes attributes) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    command.handleCompletionEvent();
    return command.isDone();
  }

  boolean handleChildWorkflowExecutionTimedOut(
      ChildWorkflowExecutionTimedOutEventAttributes attributes) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    command.handleCompletionEvent();
    return command.isDone();
  }

  boolean handleChildWorkflowExecutionTerminated(
      ChildWorkflowExecutionTerminatedEventAttributes attributes) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    command.handleCompletionEvent();
    return command.isDone();
  }

  boolean handleChildWorkflowExecutionFailed(
      ChildWorkflowExecutionFailedEventAttributes attributes) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    command.handleCompletionEvent();
    return command.isDone();
  }

  boolean handleChildWorkflowExecutionCanceled(
      ChildWorkflowExecutionCanceledEventAttributes attributes) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.CHILD_WORKFLOW, attributes.getInitiatedEventId()));
    command.handleCancellationEvent();
    return command.isDone();
  }

  void handleSignalExternalWorkflowExecutionInitiated(HistoryEvent event) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.SIGNAL_EXTERNAL_WORKFLOW, event.getEventId()));
    command.handleInitiatedEvent(event);
  }

  boolean handleTimerClosed(TimerFiredEventAttributes attributes) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.TIMER, attributes.getStartedEventId()));
    command.handleCompletionEvent();
    return command.isDone();
  }

  boolean handleTimerCanceled(HistoryEvent event) {
    TimerCanceledEventAttributes attributes = event.getTimerCanceledEventAttributes();
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.TIMER, attributes.getStartedEventId()));
    command.handleCancellationEvent();
    return command.isDone();
  }

  boolean handleCancelTimerFailed(HistoryEvent event) {
    long startedEventId = event.getEventId();
    CommandStateMachine command = getDecision(new CommandId(DecisionTarget.TIMER, startedEventId));
    command.handleCancellationFailureEvent(event);
    return command.isDone();
  }

  void handleTimerStarted(HistoryEvent event) {
    CommandStateMachine command =
        getDecision(new CommandId(DecisionTarget.TIMER, event.getEventId()));
    // Timer started event is indeed initiation event for the timer as
    // it doesn't have a separate event for started as an activity does.
    command.handleInitiatedEvent(event);
  }

  /** This happens during strongly consistent query processing for completed workflows */
  public void handleWorkflowExecutionCompleted(HistoryEvent event) {
    CommandId commandId = new CommandId(DecisionTarget.SELF, 0);
    CommandStateMachine command = getDecision(commandId);
    if (!(command instanceof CompleteWorkflowStateMachine)) {
      throw new IllegalStateException("Unexpected command: " + command);
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
    Command command =
        Command.newBuilder()
            .setCompleteWorkflowExecutionCommandAttributes(attributes)
            .setCommandType(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION)
            .build();
    CommandId commandId = new CommandId(DecisionTarget.SELF, 0);
    addDecision(commandId, new CompleteWorkflowStateMachine(commandId, command));
  }

  void continueAsNewWorkflowExecution(ContinueAsNewWorkflowExecutionCommandAttributes attributes) {
    addAllMissingVersionMarker();

    HistoryEvent firstEvent = task.getHistory().getEvents(0);
    if (!firstEvent.hasWorkflowExecutionStartedEventAttributes()) {
      throw new IllegalStateException(
          "The first event is not WorkflowExecutionStarted: " + firstEvent);
    }

    Command command =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION)
            .setContinueAsNewWorkflowExecutionCommandAttributes(attributes)
            .build();

    CommandId commandId = new CommandId(DecisionTarget.SELF, 0);
    addDecision(commandId, new CompleteWorkflowStateMachine(commandId, command));
  }

  void failWorkflowExecution(WorkflowExecutionException exception) {
    addAllMissingVersionMarker();

    FailWorkflowExecutionCommandAttributes.Builder attributes =
        FailWorkflowExecutionCommandAttributes.newBuilder().setFailure(exception.getFailure());
    Command command =
        Command.newBuilder()
            .setFailWorkflowExecutionCommandAttributes(attributes)
            .setCommandType(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION)
            .build();
    CommandId commandId = new CommandId(DecisionTarget.SELF, 0);
    addDecision(commandId, new CompleteWorkflowStateMachine(commandId, command));
  }

  /**
   * @return <code>false</code> means that cancel failed, <code>true</code> that
   *     CancelWorkflowExecution was created.
   */
  void cancelWorkflowExecution() {
    addAllMissingVersionMarker();

    Command command =
        Command.newBuilder()
            .setCancelWorkflowExecutionCommandAttributes(
                CancelWorkflowExecutionCommandAttributes.getDefaultInstance())
            .setCommandType(CommandType.COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION)
            .build();
    CommandId commandId = new CommandId(DecisionTarget.SELF, 0);
    addDecision(commandId, new CompleteWorkflowStateMachine(commandId, command));
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
    Command command =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
            .setRecordMarkerCommandAttributes(marker)
            .build();
    long nextDecisionEventId = getNextDecisionEventId();
    CommandId commandId = new CommandId(DecisionTarget.MARKER, nextDecisionEventId);
    addDecision(commandId, new MarkerCommandStateMachine(commandId, command));
  }

  void upsertSearchAttributes(SearchAttributes searchAttributes) {
    Command command =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES)
            .setUpsertWorkflowSearchAttributesCommandAttributes(
                UpsertWorkflowSearchAttributesCommandAttributes.newBuilder()
                    .setSearchAttributes(searchAttributes))
            .build();
    long nextDecisionEventId = getNextDecisionEventId();
    CommandId commandId =
        new CommandId(DecisionTarget.UPSERT_SEARCH_ATTRIBUTES, nextDecisionEventId);
    addDecision(commandId, new UpsertSearchAttributesCommandStateMachine(commandId, command));
  }

  List<Command> getDecisions() {
    List<Command> result = new ArrayList<>(MAXIMUM_DECISIONS_PER_COMPLETION + 1);
    for (CommandStateMachine commandStateMachine : decisions.values()) {
      Command command = commandStateMachine.getCommand();
      if (command != null) {
        result.add(command);
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

  private boolean isCompletionEvent(Command command) {
    CommandType type = command.getCommandType();
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

  public void handleWorkflowTaskStartedEvent(DecisionEvents command) {
    this.decisionEvents = command;
    this.nextDecisionEventId = command.getNextDecisionEventId();
    // Account for DecisionCompleted
    this.lastStartedEventId = command.getNextDecisionEventId() - 2;
  }

  void notifyDecisionSent() {
    int count = 0;
    Iterator<CommandStateMachine> iterator = decisions.values().iterator();
    CommandStateMachine next = null;

    CommandStateMachine commandStateMachine = getNextDecision(iterator);
    while (commandStateMachine != null) {
      next = getNextDecision(iterator);
      if (++count == MAXIMUM_DECISIONS_PER_COMPLETION
          && next != null
          && !isCompletionEvent(next.getCommand())) {
        break;
      }
      commandStateMachine.handleWorkflowTaskStartedEvent();
      commandStateMachine = next;
    }
    if (next != null && count < MAXIMUM_DECISIONS_PER_COMPLETION) {
      next.handleWorkflowTaskStartedEvent();
    }
  }

  private CommandStateMachine getNextDecision(Iterator<CommandStateMachine> iterator) {
    CommandStateMachine result = null;
    while (result == null && iterator.hasNext()) {
      result = iterator.next();
      if (result.getCommand() == null) {
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
  // Be careful that addAllMissingVersionMarker can add command and hence change
  // nextDecisionEventId, so any call to determine the event ID for the next command should happen
  // after that.
  private void addDecision(CommandId commandId, CommandStateMachine command) {
    Objects.requireNonNull(commandId);
    decisions.put(commandId, command);
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
   * As getVersion calls can be added and removed any time this method inserts missing command
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
      CommandId markerCommandId = new CommandId(DecisionTarget.MARKER, nextDecisionEventId);
      decisions.put(
          markerCommandId, new MarkerCommandStateMachine(markerCommandId, markerDecision));
      nextDecisionEventId++;
      markerEvent = getVersionMakerEvent(nextDecisionEventId);
    } while (markerEvent.isPresent()
        && (changeIdMarkerEventId < 0 || nextDecisionEventId < changeIdMarkerEventId));
  }

  private CommandStateMachine getDecision(CommandId commandId) {
    CommandStateMachine result = decisions.get(commandId);
    if (result == null) {
      throw new NonDeterminisicWorkflowError(
          "Unknown " + commandId + ". " + NON_DETERMINISTIC_MESSAGE);
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
