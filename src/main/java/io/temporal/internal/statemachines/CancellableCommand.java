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
import io.temporal.api.history.v1.HistoryEvent;
import java.util.Objects;

class CancellableCommand {

  private final Command command;
  private final EntityStateMachine commands;
  private boolean canceled;

  public CancellableCommand(Command command, EntityStateMachine commands) {
    this.command = Objects.requireNonNull(command);
    this.commands = Objects.requireNonNull(commands);
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
    return commands;
  }

  public CommandType getCommandType() {
    return command.getCommandType();
  }

  public void handleCommand(CommandType commandType) {
    if (!canceled) {
      commands.handleCommand(commandType);
    }
  }

  public WorkflowStateMachines.HandleEventStatus handleEvent(
      HistoryEvent event, boolean hasNextEvent) {
    if (canceled) {
      return WorkflowStateMachines.HandleEventStatus.NOT_MATCHING_EVENT;
    }
    return commands.handleEvent(event, hasNextEvent);
  }

  @Override
  public String toString() {
    return "NewCommand{" + "command=" + command + ", canceled=" + canceled + '}';
  }

  public void handleWorkflowTaskStarted() {
    commands.handleWorkflowTaskStarted();
  }
}
