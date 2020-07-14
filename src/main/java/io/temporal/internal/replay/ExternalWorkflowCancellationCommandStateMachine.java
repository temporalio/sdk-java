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
import io.temporal.api.command.v1.RequestCancelExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.history.v1.HistoryEvent;

final class ExternalWorkflowCancellationCommandStateMachine extends CommandStateMachineBase {

  private RequestCancelExternalWorkflowExecutionCommandAttributes attributes;

  ExternalWorkflowCancellationCommandStateMachine(
      CommandId commandId, RequestCancelExternalWorkflowExecutionCommandAttributes attributes) {
    super(commandId);
    this.attributes = attributes;
  }

  @Override
  public Command getCommand() {
    switch (state) {
      case CREATED:
        return createRequestCancelExternalWorkflowExecutionCommand();
      default:
        return null;
    }
  }

  @Override
  public boolean cancel(Runnable immediateCancellationCallback) {
    stateHistory.add("cancel");
    failStateTransition();
    return false;
  }

  @Override
  public void handleInitiatedEvent(HistoryEvent event) {
    stateHistory.add("handleInitiatedEvent");
    switch (state) {
      case DECISION_SENT:
        state = DecisionState.INITIATED;
        break;
      default:
        failStateTransition();
    }
    stateHistory.add(state.toString());
  }

  @Override
  public void handleInitiationFailedEvent(HistoryEvent event) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleStartedEvent(HistoryEvent event) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleCompletionEvent() {
    stateHistory.add("handleCompletionEvent");
    switch (state) {
      case DECISION_SENT:
      case INITIATED:
        state = DecisionState.COMPLETED;
        break;
      default:
        failStateTransition();
    }
    stateHistory.add(state.toString());
  }

  @Override
  public void handleCancellationInitiatedEvent() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleCancellationFailureEvent(HistoryEvent event) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void handleCancellationEvent() {
    throw new UnsupportedOperationException();
  }

  private Command createRequestCancelExternalWorkflowExecutionCommand() {
    Command decision =
        Command.newBuilder()
            .setRequestCancelExternalWorkflowExecutionCommandAttributes(attributes)
            .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION)
            .build();
    return decision;
  }
}
