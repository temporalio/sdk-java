package io.temporal.internal.statemachines;

import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.protocol.v1.Message;

interface EntityStateMachine {

  void handleCommand(CommandType commandType);

  void handleMessage(Message message);

  WorkflowStateMachines.HandleEventStatus handleEvent(HistoryEvent event, boolean hasNextEvent);

  void handleWorkflowTaskStarted();

  boolean isFinalState();
}
