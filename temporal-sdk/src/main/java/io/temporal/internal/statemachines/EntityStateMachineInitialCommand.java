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

import io.temporal.api.command.v1.Command;
import io.temporal.api.enums.v1.CommandType;
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
    command.cancel();
  }

  protected long getInitialCommandEventId() {
    return initialCommandEventId;
  }

  /** Sets initialCommandEventId to the currentEvent eventId. */
  protected void setInitialCommandEventId() {
    this.initialCommandEventId = currentEvent.getEventId();
  }
}
