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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
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

final class MutableSideEffectStateMachine {

  private static final String MARKER_HEADER_KEY = "header";
  static final String MARKER_DATA_KEY = "data";
  static final String MARKER_SKIP_COUNT_KEY = "skipCount";
  static final String MARKER_ID_KEY = "id";
  static final String MUTABLE_SIDE_EFFECT_MARKER_NAME = "MutableSideEffect";

  private final DataConverter dataConverter = DataConverter.getDefaultInstance();
  private final String id;
  private final Functions.Func<Boolean> replaying;
  private final Functions.Proc1<CancellableCommand> commandSink;

  private Optional<Payloads> result = Optional.empty();

  private int currentSkipCount;

  private int skipCountFromMarker = Integer.MAX_VALUE;

  enum ExplicitEvent {
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

  public static final StateMachineDefinition<State, ExplicitEvent, InvocationStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, InvocationStateMachine>newInstance(
                  "MutableSideEffect",
                  State.CREATED,
                  State.MARKER_COMMAND_RECORDED,
                  State.SKIPPED_NOTIFIED)
              .add(
                  State.CREATED,
                  ExplicitEvent.CHECK_EXECUTION_STATE,
                  new State[] {State.REPLAYING, State.EXECUTING},
                  InvocationStateMachine::getExecutionState)
              .add(
                  State.EXECUTING,
                  ExplicitEvent.SCHEDULE,
                  new State[] {State.MARKER_COMMAND_CREATED, State.SKIPPED},
                  InvocationStateMachine::createMarker)
              .add(
                  State.REPLAYING,
                  ExplicitEvent.SCHEDULE,
                  State.MARKER_COMMAND_CREATED_REPLAYING,
                  InvocationStateMachine::createFakeCommand)
              .add(
                  State.MARKER_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_RECORD_MARKER,
                  State.RESULT_NOTIFIED,
                  InvocationStateMachine::notifyCachedResult)
              .add(
                  State.SKIPPED,
                  CommandType.COMMAND_TYPE_RECORD_MARKER,
                  State.SKIPPED_NOTIFIED,
                  InvocationStateMachine::cancelCommandNotifyCachedResult)
              .add(
                  State.RESULT_NOTIFIED,
                  EventType.EVENT_TYPE_MARKER_RECORDED,
                  State.MARKER_COMMAND_RECORDED)
              .add(
                  State.MARKER_COMMAND_CREATED_REPLAYING,
                  CommandType.COMMAND_TYPE_RECORD_MARKER,
                  State.RESULT_NOTIFIED_REPLAYING)
              .add(
                  State.RESULT_NOTIFIED_REPLAYING,
                  ExplicitEvent.NON_MATCHING_EVENT,
                  State.SKIPPED_NOTIFIED,
                  InvocationStateMachine::cancelCommandNotifyCachedResult)
              .add(
                  State.RESULT_NOTIFIED_REPLAYING,
                  EventType.EVENT_TYPE_MARKER_RECORDED,
                  new State[] {State.MARKER_COMMAND_RECORDED, State.SKIPPED_NOTIFIED},
                  InvocationStateMachine::notifyFromEvent);

  /** Represents a single invocation of mutableSideEffect. */
  @VisibleForTesting
  class InvocationStateMachine
      extends EntityStateMachineInitialCommand<State, ExplicitEvent, InvocationStateMachine> {

    private final Functions.Proc1<Optional<Payloads>> resultCallback;
    private final Functions.Func1<Optional<Payloads>, Optional<Payloads>> func;

    InvocationStateMachine(
        Functions.Func1<Optional<Payloads>, Optional<Payloads>> func,
        Functions.Proc1<Optional<Payloads>> callback,
        Functions.Proc1<StateMachine> stateMachineSink) {
      super(
          STATE_MACHINE_DEFINITION,
          MutableSideEffectStateMachine.this.commandSink,
          stateMachineSink);
      this.func = Objects.requireNonNull(func);
      this.resultCallback = Objects.requireNonNull(callback);
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
              .equals(MUTABLE_SIDE_EFFECT_MARKER_NAME)) {
        explicitEvent(ExplicitEvent.NON_MATCHING_EVENT);
        return WorkflowStateMachines.HandleEventStatus.NON_MATCHING_EVENT;
      }
      Map<String, Payloads> detailsMap = event.getMarkerRecordedEventAttributes().getDetailsMap();
      Optional<Payloads> idPayloads = Optional.ofNullable(detailsMap.get(MARKER_ID_KEY));
      String expectedId = dataConverter.fromPayloads(0, idPayloads, String.class, String.class);
      if (Strings.isNullOrEmpty(expectedId)) {
        throw new IllegalStateException(
            "Marker details map missing required key: " + MARKER_ID_KEY);
      }
      if (!id.equals(expectedId)) {
        explicitEvent(ExplicitEvent.NON_MATCHING_EVENT);
        return WorkflowStateMachines.HandleEventStatus.NON_MATCHING_EVENT;
      }
      return super.handleEvent(event, hasNextEvent);
    }

    State createMarker() {
      Optional<Payloads> updated = func.apply(result);
      if (!updated.isPresent()) {
        currentSkipCount++;
        addCommand(StateMachineCommandUtils.RECORD_MARKER_FAKE_COMMAND);
        return State.SKIPPED;
      } else {
        result = updated;
        DataConverter dataConverter = DataConverter.getDefaultInstance();
        Map<String, Payloads> details = new HashMap<>();
        details.put(MARKER_ID_KEY, dataConverter.toPayloads(id).get());
        details.put(MARKER_DATA_KEY, updated.get());
        details.put(MARKER_SKIP_COUNT_KEY, dataConverter.toPayloads(currentSkipCount).get());
        RecordMarkerCommandAttributes markerAttributes =
            RecordMarkerCommandAttributes.newBuilder()
                .setMarkerName(MUTABLE_SIDE_EFFECT_MARKER_NAME)
                .putAllDetails(details)
                .build();
        addCommand(StateMachineCommandUtils.createRecordMarker(markerAttributes));
        currentSkipCount = 0;
        return State.MARKER_COMMAND_CREATED;
      }
    }

    void createFakeCommand() {
      addCommand(StateMachineCommandUtils.RECORD_MARKER_FAKE_COMMAND);
    }

    /**
     * Returns MARKER_COMMAND_RECORDED only if the currentEvent:
     *
     * <ul>
     *   <li>Is a Marker
     *   <li>Has MutableSideEffect marker name
     *   <li>Its skip count matches. Not matching access count means that current event is for a
     *       some following mutableSideEffect invocation.
     * </ul>
     */
    State notifyFromEvent() {
      State r = notifyFromEventImpl();
      notifyCachedResult();
      return r;
    }

    State notifyFromEventImpl() {
      MarkerRecordedEventAttributes attributes = currentEvent.getMarkerRecordedEventAttributes();
      Map<String, Payloads> detailsMap = attributes.getDetailsMap();
      Optional<Payloads> skipCountPayloads =
          Optional.ofNullable(detailsMap.get(MARKER_SKIP_COUNT_KEY));
      if (!skipCountPayloads.isPresent()) {
        throw new IllegalStateException(
            "Marker details detailsMap missing required key: " + MARKER_SKIP_COUNT_KEY);
      }
      Optional<Payloads> oid = Optional.ofNullable(detailsMap.get(MARKER_ID_KEY));
      String idFromMarker = dataConverter.fromPayloads(0, oid, String.class, String.class);
      if (!id.equals(idFromMarker)) {
        throw new IllegalArgumentException("Ids doesnt match: " + id + "<>" + idFromMarker);
      }
      skipCountFromMarker =
          dataConverter.fromPayloads(0, skipCountPayloads, Integer.class, Integer.class);
      if (++currentSkipCount < skipCountFromMarker) {
        skipCountFromMarker = Integer.MAX_VALUE;
        return State.SKIPPED_NOTIFIED;
      }
      if (!attributes.getMarkerName().equals(MUTABLE_SIDE_EFFECT_MARKER_NAME)) {
        throw new IllegalStateException(
            "Expected " + MUTABLE_SIDE_EFFECT_MARKER_NAME + ", received: " + attributes);
      }
      currentSkipCount = 0;
      result = Optional.ofNullable(detailsMap.get(MARKER_DATA_KEY));
      return State.MARKER_COMMAND_RECORDED;
    }

    void notifyCachedResult() {
      resultCallback.apply(result);
    }

    void cancelCommandNotifyCachedResult() {
      cancelCommand();
      notifyCachedResult();
    }
  }

  /** Creates new MutableSideEffectStateMachine */
  public static MutableSideEffectStateMachine newInstance(
      String id,
      Functions.Func<Boolean> replaying,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    return new MutableSideEffectStateMachine(id, replaying, commandSink, stateMachineSink);
  }

  private MutableSideEffectStateMachine(
      String id,
      Functions.Func<Boolean> replaying,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    this.id = Objects.requireNonNull(id);
    this.replaying = Objects.requireNonNull(replaying);
    this.commandSink = Objects.requireNonNull(commandSink);
  }

  public void mutableSideEffect(
      Functions.Func1<Optional<Payloads>, Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback,
      Functions.Proc1<StateMachine> stateMachineSink) {
    InvocationStateMachine ism = new InvocationStateMachine(func, callback, stateMachineSink);
    ism.explicitEvent(ExplicitEvent.CHECK_EXECUTION_STATE);
    ism.explicitEvent(ExplicitEvent.SCHEDULE);
  }
}
