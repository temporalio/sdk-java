package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RequestCancelNexusOperationCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.workflow.Functions;

/** CancelNexusOperationStateMachine manges a request to cancel a nexus operation. */
final class CancelNexusOperationStateMachine
    extends EntityStateMachineInitialCommand<
        CancelNexusOperationStateMachine.State,
        CancelNexusOperationStateMachine.ExplicitEvent,
        CancelNexusOperationStateMachine> {

  private final RequestCancelNexusOperationCommandAttributes requestCancelNexusAttributes;

  private final Functions.Proc2<Void, Failure> completionCallback;

  /**
   * @param attributes attributes to use to cancel a nexus operation
   * @param commandSink sink to send commands
   */
  public static void newInstance(
      RequestCancelNexusOperationCommandAttributes attributes,
      Functions.Proc2<Void, Failure> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    new CancelNexusOperationStateMachine(
        attributes, completionCallback, commandSink, stateMachineSink);
  }

  private CancelNexusOperationStateMachine(
      RequestCancelNexusOperationCommandAttributes attributes,
      Functions.Proc2<Void, Failure> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.requestCancelNexusAttributes = attributes;
    this.completionCallback = completionCallback;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE
  }

  enum State {
    CREATED,
    REQUEST_CANCEL_NEXUS_OPERATION_COMMAND_CREATED,
    REQUEST_CANCEL_NEXUS_OPERATION_COMMAND_RECORDED,
    CANCEL_REQUESTED,
    REQUEST_CANCEL_FAILED,
  }

  public static final StateMachineDefinition<State, ExplicitEvent, CancelNexusOperationStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition
              .<State, ExplicitEvent, CancelNexusOperationStateMachine>newInstance(
                  "CancelNexusOperation",
                  State.CREATED,
                  State.CANCEL_REQUESTED,
                  State.REQUEST_CANCEL_FAILED)
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
                  State.REQUEST_CANCEL_NEXUS_OPERATION_COMMAND_RECORDED,
                  EntityStateMachineInitialCommand::setInitialCommandEventId)
              .add(
                  State.REQUEST_CANCEL_NEXUS_OPERATION_COMMAND_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_COMPLETED,
                  State.CANCEL_REQUESTED,
                  CancelNexusOperationStateMachine::notifyCompleted)
              .add(
                  State.REQUEST_CANCEL_NEXUS_OPERATION_COMMAND_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_FAILED,
                  State.REQUEST_CANCEL_FAILED,
                  CancelNexusOperationStateMachine::notifyFailed);

  private void createCancelNexusCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_NEXUS_OPERATION)
            .setRequestCancelNexusOperationCommandAttributes(requestCancelNexusAttributes)
            .build());
  }

  private void notifyCompleted() {
    Failure canceledFailure =
        Failure.newBuilder()
            .setMessage("operation canceled")
            .setCanceledFailureInfo(CanceledFailureInfo.getDefaultInstance())
            .build();
    completionCallback.apply(null, canceledFailure);
  }

  private void notifyFailed() {
    Failure failure =
        currentEvent.getNexusOperationCancelRequestFailedEventAttributes().getFailure();
    completionCallback.apply(null, failure);
  }
}
