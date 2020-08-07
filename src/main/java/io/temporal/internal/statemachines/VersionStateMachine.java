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

import static io.temporal.internal.sync.WorkflowInternal.DEFAULT_VERSION;

import com.google.common.base.Strings;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.common.converter.DataConverter;
import io.temporal.workflow.Functions;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class VersionStateMachine {

  private static final String MARKER_HEADER_KEY = "header";
  static final String MARKER_VERSION_KEY = "version";
  static final String MARKER_CHANGE_ID_KEY = "changeId";
  static final String VERSION_MARKER_NAME = "Version";

  private final DataConverter dataConverter = DataConverter.getDefaultInstance();
  private final String changeId;
  private final Functions.Func<Boolean> replaying;
  private final Functions.Proc1<NewCommand> commandSink;

  private Optional<Integer> version = Optional.empty();

  enum Action {
    CHECK_EXECUTION_STATE,
    SCHEDULE,
    NON_MATCHING_EVENT
  }

  enum State {
    CREATED,
    REPLAYING,
    EXECUTING,
    MARKER_COMMAND_CREATED,
    SKIPPED,
    CACHED_RESULT_NOTIFIED,
    RESULT_NOTIFIED,
    SKIPPED_NOTIFIED,
    RESULT_NOTIFIED_REPLAYING,
    MARKER_COMMAND_CREATED_REPLAYING,
    MARKER_COMMAND_RECORDED,
  }

  private static StateMachine<State, Action, InvocationStateMachine> newInvocationStateMachine() {
    return StateMachine.<State, Action, InvocationStateMachine>newInstance(
            "Version", State.CREATED, State.MARKER_COMMAND_RECORDED, State.SKIPPED_NOTIFIED)
        .add(
            State.CREATED,
            Action.CHECK_EXECUTION_STATE,
            new State[] {State.REPLAYING, State.EXECUTING},
            InvocationStateMachine::getExecutionState)
        .add(
            State.EXECUTING,
            Action.SCHEDULE,
            new State[] {State.MARKER_COMMAND_CREATED, State.SKIPPED},
            InvocationStateMachine::createMarker)
        .add(
            State.MARKER_COMMAND_CREATED,
            CommandType.COMMAND_TYPE_RECORD_MARKER,
            State.RESULT_NOTIFIED,
            InvocationStateMachine::notifyResult)
        .add(
            State.RESULT_NOTIFIED,
            EventType.EVENT_TYPE_MARKER_RECORDED,
            State.MARKER_COMMAND_RECORDED)
        .add(
            State.SKIPPED,
            CommandType.COMMAND_TYPE_RECORD_MARKER,
            State.SKIPPED_NOTIFIED,
            InvocationStateMachine::cancelCommandNotifyCachedResult)
        .add(
            State.REPLAYING,
            Action.SCHEDULE,
            State.MARKER_COMMAND_CREATED_REPLAYING,
            InvocationStateMachine::createFakeCommand)
        .add(
            State.MARKER_COMMAND_CREATED_REPLAYING,
            CommandType.COMMAND_TYPE_RECORD_MARKER,
            State.RESULT_NOTIFIED_REPLAYING)
        .add(
            State.RESULT_NOTIFIED_REPLAYING,
            Action.NON_MATCHING_EVENT,
            State.SKIPPED_NOTIFIED,
            InvocationStateMachine::missingMarkerNotifyCachedOrDefault)
        .add(
            State.RESULT_NOTIFIED_REPLAYING,
            EventType.EVENT_TYPE_MARKER_RECORDED,
            new State[] {State.MARKER_COMMAND_RECORDED, State.SKIPPED_NOTIFIED},
            InvocationStateMachine::notifyFromEvent);
  }

  /** Represents a single invocation of version. */
  private class InvocationStateMachine
      extends EntityStateMachineInitialCommand<State, Action, InvocationStateMachine> {

    private final int minSupported;
    private final int maxSupported;

    private final Functions.Proc1<Integer> resultCallback;

    InvocationStateMachine(int minSupported, int maxSupported, Functions.Proc1<Integer> callback) {
      super(newInvocationStateMachine(), VersionStateMachine.this.commandSink);
      this.minSupported = minSupported;
      this.maxSupported = maxSupported;
      this.resultCallback = Objects.requireNonNull(callback);
    }

    private void validateVersion() {
      if (!version.isPresent()) {
        throw new IllegalStateException("Version not set");
      }
      int v = version.get();
      if ((v < minSupported || v > maxSupported) && v != DEFAULT_VERSION) {
        throw new Error(
            String.format(
                "Version %d of changeId %s is not supported. Supported v is between %d and %d.",
                v, changeId, minSupported, maxSupported));
      }
    }

    State getExecutionState() {
      return replaying.apply() ? State.REPLAYING : State.EXECUTING;
    }

    @Override
    public WorkflowStateMachines.HandleEventStatus handleEvent(
        HistoryEvent event, boolean hasNextEvent) {
      if (event.getEventType() != EventType.EVENT_TYPE_MARKER_RECORDED
          || !event
              .getMarkerRecordedEventAttributes()
              .getMarkerName()
              .equals(VERSION_MARKER_NAME)) {
        action(Action.NON_MATCHING_EVENT);
        return WorkflowStateMachines.HandleEventStatus.NOT_MATCHING_EVENT;
      }
      Map<String, Payloads> detailsMap = event.getMarkerRecordedEventAttributes().getDetailsMap();
      Optional<Payloads> idPayloads = Optional.ofNullable(detailsMap.get(MARKER_CHANGE_ID_KEY));
      String expectedId = dataConverter.fromPayloads(0, idPayloads, String.class, String.class);
      if (Strings.isNullOrEmpty(expectedId)) {
        throw new IllegalStateException(
            "Marker details map missing required key: " + MARKER_CHANGE_ID_KEY);
      }
      if (!changeId.equals(expectedId)) {
        // Do not call action(Action.NON_MATCHING_EVENT) here as the event with different changeId
        // still can be followed by an event with our changeId.
        return WorkflowStateMachines.HandleEventStatus.NOT_MATCHING_EVENT;
      }
      super.handleEvent(event, hasNextEvent);
      return WorkflowStateMachines.HandleEventStatus.OK;
    }

    @Override
    public void handleWorkflowTaskStarted() {
      // Needed to support getVersion calls added after this part of the workflow code has executed.
      // Accounts for the case when there are no events following the expected version marker.
      if (getState() == State.RESULT_NOTIFIED_REPLAYING) {
        action(Action.NON_MATCHING_EVENT);
      }
    }

    State createMarker() {
      State toState;
      RecordMarkerCommandAttributes markerAttributes;
      if (version.isPresent()) {
        validateVersion();
        markerAttributes = RecordMarkerCommandAttributes.getDefaultInstance();
        toState = State.SKIPPED;
      } else {
        version = Optional.of(maxSupported);
        DataConverter dataConverter = DataConverter.getDefaultInstance();
        Map<String, Payloads> details = new HashMap<>();
        details.put(MARKER_CHANGE_ID_KEY, dataConverter.toPayloads(changeId).get());
        details.put(MARKER_VERSION_KEY, dataConverter.toPayloads(version.get()).get());
        markerAttributes =
            RecordMarkerCommandAttributes.newBuilder()
                .setMarkerName(VERSION_MARKER_NAME)
                .putAllDetails(details)
                .build();
        toState = State.MARKER_COMMAND_CREATED;
      }
      addCommand(
          Command.newBuilder()
              .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
              .setRecordMarkerCommandAttributes(markerAttributes)
              .build());
      return toState;
    }

    void createFakeCommand() {
      addCommand(
          Command.newBuilder()
              .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
              .setRecordMarkerCommandAttributes(RecordMarkerCommandAttributes.getDefaultInstance())
              .build());
    }

    /**
     * Returns MARKER_COMMAND_RECORDED only if the currentEvent:
     *
     * <ul>
     *   <li>Is a Marker
     *   <li>Has Version marker name
     *   <li>Its skip count matches. Not matching access count means that current event is for a
     *       some following version invocation.
     * </ul>
     */
    State notifyFromEvent() {
      State r = notifyFromEventImpl();
      notifyResult();
      return r;
    }

    State notifyFromEventImpl() {
      updateVersionFromEvent(currentEvent);
      validateVersion();
      return State.MARKER_COMMAND_RECORDED;
    }

    void notifyResult() {
      resultCallback.apply(version.get());
    }

    void cancelCommandNotifyCachedResult() {
      cancelInitialCommand();
      notifyResult();
    }

    void missingMarkerNotifyCachedOrDefault() {
      cancelInitialCommand();
      if (!version.isPresent()) {
        version = Optional.of(DEFAULT_VERSION);
      }
      notifyResult();
    }
  }

  private void updateVersionFromEvent(HistoryEvent event) {
    if (version.isPresent()) {
      throw new IllegalStateException(
          "Version is already set to "
              + version.get()
              + ". The most probable cause is retroactive addition "
              + "of a getVersion call with an existing 'changeId'");
    }
    MarkerRecordedEventAttributes attributes = event.getMarkerRecordedEventAttributes();
    if (!attributes.getMarkerName().equals(VERSION_MARKER_NAME)) {
      throw new IllegalStateException(
          "Expected " + VERSION_MARKER_NAME + ", received: " + attributes);
    }
    Map<String, Payloads> detailsMap = attributes.getDetailsMap();
    Optional<Payloads> oid = Optional.ofNullable(detailsMap.get(MARKER_CHANGE_ID_KEY));
    String idFromMarker = dataConverter.fromPayloads(0, oid, String.class, String.class);
    if (!changeId.equals(idFromMarker)) {
      throw new UnsupportedOperationException(
          "TODO: deal with multiple side effects with different id");
    }
    Optional<Payloads> skipCountPayloads = Optional.ofNullable(detailsMap.get(MARKER_VERSION_KEY));
    if (!skipCountPayloads.isPresent()) {
      throw new IllegalStateException(
          "Marker details detailsMap missing required key: " + MARKER_VERSION_KEY);
    }
    int v = dataConverter.fromPayloads(0, skipCountPayloads, Integer.class, Integer.class);
    version = Optional.of(v);
  }

  /** Creates new VersionStateMachine */
  public static VersionStateMachine newInstance(
      String id, Functions.Func<Boolean> replaying, Functions.Proc1<NewCommand> commandSink) {
    return new VersionStateMachine(id, replaying, commandSink);
  }

  private VersionStateMachine(
      String changeId, Functions.Func<Boolean> replaying, Functions.Proc1<NewCommand> commandSink) {
    this.changeId = Objects.requireNonNull(changeId);
    this.replaying = Objects.requireNonNull(replaying);
    this.commandSink = Objects.requireNonNull(commandSink);
  }

  public void getVersion(int minSupported, int maxSupported, Functions.Proc1<Integer> callback) {
    InvocationStateMachine ism = new InvocationStateMachine(minSupported, maxSupported, callback);
    ism.action(Action.CHECK_EXECUTION_STATE);
    ism.action(Action.SCHEDULE);
  }

  public void handleNonMatchingEvent(HistoryEvent event) {
    updateVersionFromEvent(event);
  }

  public static String asPlantUMLStateDiagram() {
    return newInvocationStateMachine().asPlantUMLStateDiagram();
  }
}
