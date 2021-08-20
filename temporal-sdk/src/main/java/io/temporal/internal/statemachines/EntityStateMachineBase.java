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

import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.workflow.Functions;

class EntityStateMachineBase<State, ExplicitEvent, Data> implements EntityStateMachine {

  private final StateMachine<State, ExplicitEvent, Data> stateMachine;

  protected final Functions.Proc1<CancellableCommand> commandSink;

  protected HistoryEvent currentEvent;
  protected boolean hasNextEvent;

  public EntityStateMachineBase(
      StateMachineDefinition<State, ExplicitEvent, Data> stateMachineDefinition,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    this.stateMachine = StateMachine.newInstance(stateMachineDefinition);
    this.commandSink = commandSink;
    stateMachineSink.apply(this.stateMachine);
  }

  /**
   * Notifies that command is included into the workflow task completion result.
   *
   * <p>Is not called for commands generated during replay.
   */
  @Override
  public void handleCommand(CommandType commandType) {
    stateMachine.handleCommand(commandType, (Data) this);
  }

  @Override
  public WorkflowStateMachines.HandleEventStatus handleEvent(
      HistoryEvent event, boolean hasNextEvent) {
    if (!stateMachine.getValidEventTypes().contains(event.getEventType())) {
      return WorkflowStateMachines.HandleEventStatus.NON_MATCHING_EVENT;
    }
    this.currentEvent = event;
    this.hasNextEvent = hasNextEvent;
    try {
      stateMachine.handleHistoryEvent(event.getEventType(), (Data) this);
    } finally {
      this.currentEvent = null;
    }
    return WorkflowStateMachines.HandleEventStatus.OK;
  }

  @Override
  public void handleWorkflowTaskStarted() {}

  protected final void explicitEvent(ExplicitEvent explicitEvent) {
    stateMachine.handleExplicitEvent(explicitEvent, (Data) this);
  }

  @Override
  public boolean isFinalState() {
    return stateMachine.isFinalState();
  }

  protected State getState() {
    return stateMachine.getState();
  }

  @Override
  public String toString() {
    return this.getClass().getSimpleName()
        + "{"
        + "stateMachine="
        + stateMachine
        + ", hasNextEvent="
        + hasNextEvent
        + ", currentEvent="
        + currentEvent
        + '}';
  }
}
