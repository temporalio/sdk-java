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
