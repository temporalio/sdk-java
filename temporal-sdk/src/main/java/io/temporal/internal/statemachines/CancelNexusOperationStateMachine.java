package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RequestCancelNexusOperationCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.workflow.Functions;

/** CancelNexusOperationStateMachine manges a request to cancel a nexus operation. */
final class CancelNexusOperationStateMachine
    extends EntityStateMachineInitialCommand<
        CancelNexusOperationStateMachine.State,
        CancelNexusOperationStateMachine.ExplicitEvent,
        CancelNexusOperationStateMachine> {

  private final RequestCancelNexusOperationCommandAttributes requestCancelNexusAttributes;

  /**
   * @param attributes attributes to use to cancel a nexus operation
   * @param commandSink sink to send commands
   */
  public static void newInstance(
      RequestCancelNexusOperationCommandAttributes attributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    new CancelNexusOperationStateMachine(attributes, commandSink, stateMachineSink);
  }

  private CancelNexusOperationStateMachine(
      RequestCancelNexusOperationCommandAttributes attributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.requestCancelNexusAttributes = attributes;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE
  }

  enum State {
    CREATED,
    REQUEST_CANCEL_NEXUS_OPERATION_COMMAND_CREATED,
    CANCEL_REQUESTED,
  }

  public static final StateMachineDefinition<State, ExplicitEvent, CancelNexusOperationStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition
              .<State, ExplicitEvent, CancelNexusOperationStateMachine>newInstance(
                  "CancelNexusOperation", State.CREATED, State.CANCEL_REQUESTED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.REQUEST_CANCEL_NEXUS_OPERATION_COMMAND_CREATED,
                  CancelNexusOperationStateMachine::createCancelNexusCommand)
              .add(
                  State.REQUEST_CANCEL_NEXUS_OPERATION_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_REQUEST_CANCEL_NEXUS_OPERATION,
                  State.REQUEST_CANCEL_NEXUS_OPERATION_COMMAND_CREATED)
              .add(
                  State.REQUEST_CANCEL_NEXUS_OPERATION_COMMAND_CREATED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED,
                  State.CANCEL_REQUESTED,
                  CancelNexusOperationStateMachine::notifyCompleted);

  private void createCancelNexusCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_NEXUS_OPERATION)
            .setRequestCancelNexusOperationCommandAttributes(requestCancelNexusAttributes)
            .build());
  }

  private void notifyCompleted() {
    setInitialCommandEventId();
  }
}
