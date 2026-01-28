package io.temporal.internal.statemachines;

import static io.temporal.internal.common.WorkflowExecutionUtils.isCommandEvent;

import io.temporal.api.command.v1.Command;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.workflow.Functions;
import javax.annotation.Nullable;

class EntityStateMachineInitialCommand<State, ExplicitEvent, Data>
    extends EntityStateMachineBase<State, ExplicitEvent, Data> {
  private CancellableCommand command;
  private long initialCommandEventId;

  public EntityStateMachineInitialCommand(
      StateMachineDefinition<State, ExplicitEvent, Data> stateMachineDefinition,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    this(stateMachineDefinition, commandSink, stateMachineSink, null);
  }

  public EntityStateMachineInitialCommand(
      StateMachineDefinition<State, ExplicitEvent, Data> stateMachineDefinition,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink,
      @Nullable String entityName) {
    super(stateMachineDefinition, commandSink, stateMachineSink, entityName);
  }

  protected final void addCommand(Command command) {
    if (command.getCommandType() == CommandType.COMMAND_TYPE_UNSPECIFIED) {
      throw new IllegalArgumentException("unspecified command type");
    }
    this.command = new CancellableCommand(command, this);
    commandSink.apply(this.command);
  }

  protected final void cancelCommand() {
    if (command != null) {
      command.cancel();
    }
  }

  public WorkflowStateMachines.HandleEventStatus handleEvent(
      HistoryEvent event, boolean hasNextEvent) {
    if (isCommandEvent(event)) {
      command = null;
    }
    return super.handleEvent(event, hasNextEvent);
  }

  protected long getInitialCommandEventId() {
    return initialCommandEventId;
  }

  /** Sets initialCommandEventId to the currentEvent eventId. */
  protected void setInitialCommandEventId() {
    this.initialCommandEventId = currentEvent.getEventId();
  }
}
