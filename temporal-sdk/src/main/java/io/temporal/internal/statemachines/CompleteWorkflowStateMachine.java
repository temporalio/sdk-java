package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.workflow.Functions;
import java.util.Optional;

final class CompleteWorkflowStateMachine
    extends EntityStateMachineInitialCommand<
        CompleteWorkflowStateMachine.State,
        CompleteWorkflowStateMachine.ExplicitEvent,
        CompleteWorkflowStateMachine> {

  private final CompleteWorkflowExecutionCommandAttributes completeWorkflowAttributes;

  public static void newInstance(
      Optional<Payloads> workflowOutput,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    CompleteWorkflowExecutionCommandAttributes.Builder attributes =
        CompleteWorkflowExecutionCommandAttributes.newBuilder();
    if (workflowOutput.isPresent()) {
      attributes.setResult(workflowOutput.get());
    }
    new CompleteWorkflowStateMachine(attributes.build(), commandSink, stateMachineSink);
  }

  private CompleteWorkflowStateMachine(
      CompleteWorkflowExecutionCommandAttributes completeWorkflowAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.completeWorkflowAttributes = completeWorkflowAttributes;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE
  }

  enum State {
    CREATED,
    COMPLETE_WORKFLOW_COMMAND_CREATED,
    COMPLETE_WORKFLOW_COMMAND_RECORDED,
  }

  public static final StateMachineDefinition<State, ExplicitEvent, CompleteWorkflowStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, CompleteWorkflowStateMachine>newInstance(
                  "CompleteWorkflow", State.CREATED, State.COMPLETE_WORKFLOW_COMMAND_RECORDED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.COMPLETE_WORKFLOW_COMMAND_CREATED,
                  CompleteWorkflowStateMachine::createCompleteWorkflowCommand)
              .add(
                  State.COMPLETE_WORKFLOW_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION,
                  State.COMPLETE_WORKFLOW_COMMAND_CREATED)
              .add(
                  State.COMPLETE_WORKFLOW_COMMAND_CREATED,
                  EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED,
                  State.COMPLETE_WORKFLOW_COMMAND_RECORDED);

  private void createCompleteWorkflowCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION)
            .setCompleteWorkflowExecutionCommandAttributes(completeWorkflowAttributes)
            .build());
  }
}
