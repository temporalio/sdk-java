package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.api.sdk.v1.UserMetadata;
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

  private UserMetadata metadata;
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
      UserMetadata metadata,
      Functions.Func<Boolean> replaying,
      Functions.Func<Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    new SideEffectStateMachine(metadata, replaying, func, callback, commandSink, stateMachineSink);
  }

  private SideEffectStateMachine(
      UserMetadata metadata,
      Functions.Func<Boolean> replaying,
      Functions.Func<Optional<Payloads>> func,
      Functions.Proc1<Optional<Payloads>> callback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.metadata = metadata;
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

    Command.Builder command =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
            .setRecordMarkerCommandAttributes(markerAttributes);

    if (metadata != null) {
      command.setUserMetadata(metadata);
      metadata = null;
    }

    addCommand(command.build());
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
