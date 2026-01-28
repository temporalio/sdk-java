package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.CancelTimerCommandAttributes;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.StartTimerCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.TimerCanceledEventAttributes;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.workflow.Functions;
import javax.annotation.Nullable;

final class TimerStateMachine
    extends EntityStateMachineInitialCommand<
        TimerStateMachine.State, TimerStateMachine.ExplicitEvent, TimerStateMachine> {

  private final StartTimerCommandAttributes startAttributes;

  private UserMetadata metadata;

  private final Functions.Proc1<HistoryEvent> completionCallback;

  /**
   * Creates a new timer state machine
   *
   * @param attributes timer command attributes
   * @param metadata user metadata to be associate with the timer
   * @param completionCallback invoked when timer fires or reports cancellation. One of
   *     TimerFiredEvent, TimerCanceledEvent.
   * @return cancellation callback that should be invoked to initiate timer cancellation
   */
  public static TimerStateMachine newInstance(
      StartTimerCommandAttributes attributes,
      @Nullable UserMetadata metadata,
      Functions.Proc1<HistoryEvent> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    return new TimerStateMachine(
        attributes, metadata, completionCallback, commandSink, stateMachineSink);
  }

  private TimerStateMachine(
      StartTimerCommandAttributes attributes,
      @Nullable UserMetadata metadata,
      Functions.Proc1<HistoryEvent> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink, attributes.getTimerId());
    this.startAttributes = attributes;
    this.metadata = metadata;
    this.completionCallback = completionCallback;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE,
    CANCEL
  }

  enum State {
    CREATED,
    START_COMMAND_CREATED,
    START_COMMAND_RECORDED,
    CANCEL_TIMER_COMMAND_CREATED,
    CANCEL_TIMER_COMMAND_SENT,
    FIRED,
    CANCELED,
  }

  public static final StateMachineDefinition<State, ExplicitEvent, TimerStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, TimerStateMachine>newInstance(
                  "Timer", State.CREATED, State.FIRED, State.CANCELED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.START_COMMAND_CREATED,
                  TimerStateMachine::createStartTimerCommand)
              .add(
                  State.START_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_START_TIMER,
                  State.START_COMMAND_CREATED)
              .add(
                  State.START_COMMAND_CREATED,
                  EventType.EVENT_TYPE_TIMER_STARTED,
                  State.START_COMMAND_RECORDED,
                  EntityStateMachineInitialCommand::setInitialCommandEventId)
              .add(
                  State.START_COMMAND_CREATED,
                  ExplicitEvent.CANCEL,
                  State.CANCELED,
                  TimerStateMachine::cancelStartTimerCommand)
              .add(
                  State.START_COMMAND_RECORDED,
                  EventType.EVENT_TYPE_TIMER_FIRED,
                  State.FIRED,
                  TimerStateMachine::notifyCompletion)
              .add(
                  State.START_COMMAND_RECORDED,
                  ExplicitEvent.CANCEL,
                  State.CANCEL_TIMER_COMMAND_CREATED,
                  TimerStateMachine::createCancelTimerCommand)
              .add(
                  State.CANCEL_TIMER_COMMAND_CREATED,
                  ExplicitEvent.CANCEL,
                  State.CANCEL_TIMER_COMMAND_CREATED)
              .add(
                  State.CANCEL_TIMER_COMMAND_SENT,
                  EventType.EVENT_TYPE_TIMER_CANCELED,
                  State.CANCELED)
              .add(
                  State.CANCEL_TIMER_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_CANCEL_TIMER,
                  State.CANCEL_TIMER_COMMAND_SENT,
                  TimerStateMachine::notifyCancellation);

  private void createStartTimerCommand() {
    Command.Builder command =
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_START_TIMER)
            .setStartTimerCommandAttributes(startAttributes);

    if (metadata != null) {
      command.setUserMetadata(metadata);
      metadata = null;
    }

    addCommand(command.build());
  }

  public void cancel() {
    if (!isFinalState()) {
      explicitEvent(ExplicitEvent.CANCEL);
    }
  }

  private void cancelStartTimerCommand() {
    cancelCommand();
    notifyCancellation();
  }

  private void notifyCancellation() {
    completionCallback.apply(
        HistoryEvent.newBuilder()
            .setEventType(EventType.EVENT_TYPE_TIMER_CANCELED)
            .setTimerCanceledEventAttributes(
                TimerCanceledEventAttributes.newBuilder()
                    .setIdentity("workflow")
                    .setTimerId(startAttributes.getTimerId()))
            .build());
  }

  private void notifyCompletion() {
    completionCallback.apply(currentEvent);
  }

  private void createCancelTimerCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_CANCEL_TIMER)
            .setCancelTimerCommandAttributes(
                CancelTimerCommandAttributes.newBuilder().setTimerId(startAttributes.getTimerId()))
            .build());
  }

  private void cancelTimerCommandFireTimer() {
    cancelCommand();
    notifyCompletion();
  }
}
