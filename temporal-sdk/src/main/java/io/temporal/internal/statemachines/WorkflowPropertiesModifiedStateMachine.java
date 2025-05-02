package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ModifyWorkflowPropertiesCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.workflow.Functions;

final class WorkflowPropertiesModifiedStateMachine
    extends EntityStateMachineInitialCommand<
        WorkflowPropertiesModifiedStateMachine.State,
        WorkflowPropertiesModifiedStateMachine.ExplicitEvent,
        WorkflowPropertiesModifiedStateMachine> {

  private ModifyWorkflowPropertiesCommandAttributes modifiedPropertiesAttributes;

  public static void newInstance(
      ModifyWorkflowPropertiesCommandAttributes modifiedPropertiesAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    new WorkflowPropertiesModifiedStateMachine(
        modifiedPropertiesAttributes, commandSink, stateMachineSink);
  }

  private WorkflowPropertiesModifiedStateMachine(
      ModifyWorkflowPropertiesCommandAttributes modifiedPropertiesAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.modifiedPropertiesAttributes = modifiedPropertiesAttributes;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE
  }

  enum State {
    CREATED,
    MODIFY_COMMAND_CREATED,
    MODIFY_COMMAND_RECORDED,
  }

  public static final StateMachineDefinition<
          State, ExplicitEvent, WorkflowPropertiesModifiedStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition
              .<State, ExplicitEvent, WorkflowPropertiesModifiedStateMachine>newInstance(
                  "WorkflowPropertiesModified", State.CREATED, State.MODIFY_COMMAND_RECORDED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.MODIFY_COMMAND_CREATED,
                  WorkflowPropertiesModifiedStateMachine::createModifyCommand)
              .add(
                  State.MODIFY_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_MODIFY_WORKFLOW_PROPERTIES,
                  State.MODIFY_COMMAND_CREATED)
              .add(
                  State.MODIFY_COMMAND_CREATED,
                  EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED,
                  State.MODIFY_COMMAND_RECORDED);

  private void createModifyCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_MODIFY_WORKFLOW_PROPERTIES)
            .setModifyWorkflowPropertiesCommandAttributes(modifiedPropertiesAttributes)
            .build());
    modifiedPropertiesAttributes = null;
  }
}
