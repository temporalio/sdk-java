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

package io.temporal.internal.replay;

import io.temporal.api.command.v1.Command;
import io.temporal.api.history.v1.HistoryEvent;

final class CompleteWorkflowStateMachine implements CommandStateMachine {

  private Command command;
  private final CommandId id;

  public CompleteWorkflowStateMachine(CommandId id, Command command) {
    this.id = id;
    this.command = command;
  }

  @Override
  public CommandId getId() {
    return id;
  }

  @Override
  public Command getCommand() {
    return command;
  }

  @Override
  public void handleInitiationFailedEvent(HistoryEvent event) {
    command = null;
  }

  @Override
  public boolean cancel(Runnable immediateCancellationCallback) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleStartedEvent(HistoryEvent event) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleCancellationEvent() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleCancellationFailureEvent(HistoryEvent event) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleCompletionEvent() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleInitiatedEvent(HistoryEvent event) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CommandState getState() {
    return CommandState.CREATED;
  }

  @Override
  public void handleCancellationInitiatedEvent() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isDone() {
    return command != null;
  }

  @Override
  public void handleWorkflowTaskStartedEvent() {}

  @Override
  public String toString() {
    return "CompleteWorkflowStateMachine [command=" + command + ", id=" + id + "]";
  }
}
