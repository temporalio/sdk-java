/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.statemachines;

import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.protocol.v1.Message;
import io.temporal.internal.common.ProtocolType;
import io.temporal.internal.common.ProtocolUtils;
import io.temporal.workflow.Functions;
import javax.annotation.Nullable;

class EntityStateMachineBase<State, ExplicitEvent, Data> implements EntityStateMachine {
  protected final StateMachine<State, ExplicitEvent, Data> stateMachine;
  protected final Functions.Proc1<CancellableCommand> commandSink;

  protected HistoryEvent currentEvent;
  protected boolean hasNextEvent;

  protected Message currentMessage;

  /**
   * @param entityName name or id of the entity this state machine represents. For debug purposes
   *     only. Can be null.
   */
  public EntityStateMachineBase(
      StateMachineDefinition<State, ExplicitEvent, Data> stateMachineDefinition,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink,
      @Nullable String entityName) {
    this.stateMachine = StateMachine.newInstance(stateMachineDefinition, entityName);
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
  public void handleMessage(Message message) {
    this.currentMessage = message;
    try {
      stateMachine.handleMessage(
          ProtocolType.get(ProtocolUtils.getProtocol(message)).get(), (Data) this);
    } finally {
      this.currentMessage = null;
    }
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
