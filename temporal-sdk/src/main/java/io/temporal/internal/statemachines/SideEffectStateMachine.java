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

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.workflow.Functions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class SideEffectStateMachine
    extends EntityStateMachineInitialCommand<
        SideEffectStateMachine.State,
        SideEffectStateMachine.ExplicitEvent,
        SideEffectStateMachine> {

  enum ExplicitEvent {
    SCHEDULE
  }

  enum State {
    CREATED,
    MARKER_COMMAND_CREATED,
    RESULT_NOTIFIED,
    RESULT_NOTIFIED_REPLAYING,
    MARKER_COMMAND_CREATED_REPLAYING,
    MARKER_COMMAND_RECORDED,
  }

  static final String MARKER_DATA_KEY = "data";
  static final String SIDE_EFFECT_MARKER_NAME = "SideEffect";

  private final Functions.Proc1<Optional<Payloads>> callback;
  private final Functions.Func<Optional<Payloads>> func;
  private final Functions.Func<Boolean> replaying;

  private Optional<Payloads> result;

  public static final StateMachineDefinition<State, ExplicitEvent, SideEffectStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, SideEffectStateMachine>newInstance(
                  "SideEffect", State.CREATED, State.MARKER_COMMAND_RECORDED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  new State[] {
                    State.MARKER_COMMAND_CREATED, State.MARKER_COMMAND_CREATED_REPLAYING
                  },
                  SideEffectStateMachine::createMarkerCommand)
              .add(
                  State.MARKER_COMMAND_CREATED_REPLAYING,
                  CommandType.COMMAND_TYPE_RECORD_MARKER,
                  State.RESULT_NOTIFIED_REPLAYING)
              .add(
                  State.MARKER_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_RECORD_MARKER,
                  State.RESULT_NOTIFIED,
                  SideEffectStateMachine::markerResultFromFunc)
              .add(
                  State.RESULT_NOTIFIED,
                  EventType.EVENT_TYPE_MARKER_RECORDED,
                  State.MARKER_COMMAND_RECORDED)
              .add(
                  State.RESULT_NOTIFIED_REPLAYING,
                  EventType.EVENT_TYPE_MARKER_RECORDED,
                  State.MARKER_COMMAND_RECORDED,
                  SideEffectStateMachine::markerResultFromEvent);

  /**
   * Creates new SideEffect Marker
   *
   * @param func used to produce side effect value. null if replaying.
   * @param callback returns side effect value or failure
   * @param commandSink callback to send commands to
   */
  public static void newInstance(
      Functions.Func<Boolean> replaying,
      Functions.Func<Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    new SideEffectStateMachine(replaying, func, callback, commandSink, stateMachineSink);
  }

  private SideEffectStateMachine(
      Functions.Func<Boolean> replaying,
      Functions.Func<Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.replaying = replaying;
    this.func = func;
    this.callback = callback;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  private State createMarkerCommand() {
    State transitionTo;
    RecordMarkerCommandAttributes markerAttributes;
    if (replaying.apply()) {
      markerAttributes = RecordMarkerCommandAttributes.getDefaultInstance();
      transitionTo = State.MARKER_COMMAND_CREATED_REPLAYING;
    } else {
      // executing first time
      result = func.apply();
      if (result == null) {
        throw new IllegalStateException("marker function returned null");
      }
      Map<String, Payloads> details = new HashMap<>();
      if (result.isPresent()) {
        details.put(MARKER_DATA_KEY, result.get());
      }
      markerAttributes =
          RecordMarkerCommandAttributes.newBuilder()
              .setMarkerName(SIDE_EFFECT_MARKER_NAME)
              .putAllDetails(details)
              .build();
      transitionTo = State.MARKER_COMMAND_CREATED;
    }
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
            .setRecordMarkerCommandAttributes(markerAttributes)
            .build());
    return transitionTo;
  }

  private void markerResultFromEvent() {
    MarkerRecordedEventAttributes attributes = currentEvent.getMarkerRecordedEventAttributes();
    if (!attributes.getMarkerName().equals(SIDE_EFFECT_MARKER_NAME)) {
      throw new IllegalStateException(
          "Expected " + SIDE_EFFECT_MARKER_NAME + ", received: " + attributes);
    }
    Map<String, Payloads> map = attributes.getDetailsMap();
    Optional<Payloads> fromMarker = Optional.ofNullable(map.get(MARKER_DATA_KEY));
    callback.apply(fromMarker);
  }

  private void markerResultFromFunc() {
    callback.apply(result);
  }
}
