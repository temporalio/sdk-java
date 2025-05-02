package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.history.v1.HistoryEvent;
import java.util.Objects;

class CancellableCommand {

  private final Command command;
  private final EntityStateMachine entityStateMachine;
  private boolean canceled;

  public CancellableCommand(Command command, EntityStateMachine entityStateMachine) {
    this.command = Objects.requireNonNull(command);
    this.entityStateMachine = Objects.requireNonNull(entityStateMachine);
  }

  public Command getCommand() {
    if (canceled) {
      throw new IllegalStateException("canceled");
    }
    return command;
  }

  public boolean isCanceled() {
    return canceled;
  }

  public void cancel() {
    canceled = true;
  }

  public EntityStateMachine getStateMachine() {
    return entityStateMachine;
  }

  public CommandType getCommandType() {
    return command.getCommandType();
  }

  public void handleCommand(CommandType commandType) {
    if (!canceled) {
      entityStateMachine.handleCommand(commandType);
    }
  }

  public WorkflowStateMachines.HandleEventStatus handleEvent(
      HistoryEvent event, boolean hasNextEvent) {
    if (canceled) {
      throw new IllegalStateException("handleEvent shouldn't be called for cancelled events");
    }
    return entityStateMachine.handleEvent(event, hasNextEvent);
  }

  @Override
  public String toString() {
    return "CancellableCommand{" + "command=" + command + ", canceled=" + canceled + '}';
  }

  public void handleWorkflowTaskStarted() {
    entityStateMachine.handleWorkflowTaskStarted();
  }
}
