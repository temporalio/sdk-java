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

class EntityStateMachineBase<State, Action, Data> implements EntityStateMachine {

  private final StateMachine<State, Action, Data> stateMachine;

  protected final Functions.Proc1<NewCommand> commandSink;

  protected HistoryEvent currentEvent;
  protected boolean hasNextEvent;

  public EntityStateMachineBase(
      StateMachine<State, Action, Data> stateMachine, Functions.Proc1<NewCommand> commandSink) {
    this.stateMachine = stateMachine;
    this.commandSink = commandSink;
  }

  /**
   * Notifies that command is included into zthe workflow task completion result.
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
      return WorkflowStateMachines.HandleEventStatus.NOT_MATCHING_EVENT;
    }
    this.currentEvent = event;
    this.hasNextEvent = hasNextEvent;
    try {
      stateMachine.handleEvent(event.getEventType(), (Data) this);
    } finally {
      this.currentEvent = null;
    }
    return null;
  }

  @Override
  public void handleWorkflowTaskStarted() {}

  protected final void action(Action action) {
    stateMachine.action(action, (Data) this);
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
    return this.getClass().getSimpleName() + "{" + "state=" + stateMachine + '}';
  }
}
