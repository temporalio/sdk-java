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
        SideEffectStateMachine.State, SideEffectStateMachine.Action, SideEffectStateMachine> {

  enum Action {
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

  private static final String MARKER_HEADER_KEY = "header";
  static final String MARKER_DATA_KEY = "data";
  static final String SIDE_EFFECT_MARKER_NAME = "SideEffect";

  private final Functions.Proc1<Optional<Payloads>> callback;
  private final Functions.Func<Optional<Payloads>> func;
  private final Functions.Func<Boolean> replaying;

  private Optional<Payloads> result;

  private static StateMachine<State, Action, SideEffectStateMachine> newStateMachine() {
    return StateMachine.<State, Action, SideEffectStateMachine>newInstance(
            "SideEffect", State.CREATED, State.MARKER_COMMAND_RECORDED)
        .add(
            State.CREATED,
            Action.SCHEDULE,
            new State[] {State.MARKER_COMMAND_CREATED, State.MARKER_COMMAND_CREATED_REPLAYING},
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
  }

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
      Functions.Proc1<NewCommand> commandSink) {
    new SideEffectStateMachine(replaying, func, callback, commandSink);
  }

  private SideEffectStateMachine(
      Functions.Func<Boolean> replaying,
      Functions.Func<Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback,
      Functions.Proc1<NewCommand> commandSink) {
    super(newStateMachine(), commandSink);
    this.replaying = replaying;
    this.func = func;
    this.callback = callback;
    action(Action.SCHEDULE);
  }

  private State createMarkerCommand() {
    RecordMarkerCommandAttributes markerAttributes;
    if (replaying.apply()) {
      markerAttributes = RecordMarkerCommandAttributes.getDefaultInstance();
      return State.MARKER_COMMAND_CREATED_REPLAYING;
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
    }
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
            .setRecordMarkerCommandAttributes(markerAttributes)
            .build());
    return State.MARKER_COMMAND_CREATED;
  }

  private void markerResultFromEvent() {
    MarkerRecordedEventAttributes attributes = currentEvent.getMarkerRecordedEventAttributes();
    if (!attributes.getMarkerName().equals(SIDE_EFFECT_MARKER_NAME)) {
      throw new IllegalStateException(
          "Expected " + SIDE_EFFECT_MARKER_NAME + ", received: " + attributes);
    }
    Map<String, Payloads> map = attributes.getDetailsMap();
    Optional<Payloads> fromMaker = Optional.ofNullable(map.get(MARKER_DATA_KEY));
    callback.apply(fromMaker);
  }

  private void markerResultFromFunc() {
    callback.apply(result);
  }

  public static String asPlantUMLStateDiagram() {
    return newStateMachine().asPlantUMLStateDiagram();
  }
}
