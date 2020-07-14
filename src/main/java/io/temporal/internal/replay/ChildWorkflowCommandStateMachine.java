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
import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.history.v1.HistoryEvent;

final class ChildWorkflowCommandStateMachine extends CommandStateMachineBase {

  private StartChildWorkflowExecutionCommandAttributes startAttributes;

  private String runId;

  public ChildWorkflowCommandStateMachine(
      CommandId id, StartChildWorkflowExecutionCommandAttributes startAttributes) {
    super(id);
    this.startAttributes = startAttributes;
  }

  /** Used for unit testing */
  ChildWorkflowCommandStateMachine(
      CommandId id,
      StartChildWorkflowExecutionCommandAttributes startAttributes,
      DecisionState state) {
    super(id, state);
    this.startAttributes = startAttributes;
  }

  @Override
  public Command getCommand() {
    switch (state) {
      case CREATED:
        return createStartChildWorkflowExecutionCommand();
      case CANCELED_AFTER_STARTED:
        return createRequestCancelExternalWorkflowExecutionCommand();
      default:
        return null;
    }
  }

  @Override
  public void handleWorkflowTaskStartedEvent() {
    switch (state) {
      case CANCELED_AFTER_STARTED:
        state = DecisionState.CANCELLATION_DECISION_SENT;
        break;
      default:
        super.handleWorkflowTaskStartedEvent();
    }
  }

  @Override
  public void handleStartedEvent(HistoryEvent event) {
    stateHistory.add("handleStartedEvent");
    switch (state) {
      case INITIATED:
        state = DecisionState.STARTED;
        break;
      case CANCELED_AFTER_INITIATED:
        state = DecisionState.CANCELED_AFTER_STARTED;
        break;
      default:
    }
    stateHistory.add(state.toString());
  }

  @Override
  public void handleCancellationFailureEvent(HistoryEvent event) {
    switch (state) {
      case CANCELLATION_DECISION_SENT:
        stateHistory.add("handleCancellationFailureEvent");
        state = DecisionState.STARTED;
        stateHistory.add(state.toString());
        break;
      default:
        super.handleCancellationFailureEvent(event);
    }
  }

  @Override
  public boolean cancel(Runnable immediateCancellationCallback) {
    switch (state) {
      case STARTED:
        stateHistory.add("cancel");
        state = DecisionState.CANCELED_AFTER_STARTED;
        stateHistory.add(state.toString());
        return true;
      default:
        return super.cancel(immediateCancellationCallback);
    }
  }

  @Override
  public void handleCancellationEvent() {
    switch (state) {
      case STARTED:
        stateHistory.add("handleCancellationEvent");
        state = DecisionState.COMPLETED;
        stateHistory.add(state.toString());
        break;
      default:
        super.handleCancellationEvent();
    }
  }

  @Override
  public void handleCompletionEvent() {
    switch (state) {
      case STARTED:
      case CANCELED_AFTER_STARTED:
        stateHistory.add("handleCompletionEvent");
        state = DecisionState.COMPLETED;
        stateHistory.add(state.toString());
        break;
      default:
        super.handleCompletionEvent();
    }
  }

  private Command createRequestCancelExternalWorkflowExecutionCommand() {
    return Command.newBuilder()
        .setRequestCancelExternalWorkflowExecutionCommandAttributes(
            RequestCancelExternalWorkflowExecutionCommandAttributes.newBuilder()
                .setWorkflowId(startAttributes.getWorkflowId())
                .setRunId(runId))
        .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION)
        .build();
  }

  private Command createStartChildWorkflowExecutionCommand() {
    return Command.newBuilder()
        .setStartChildWorkflowExecutionCommandAttributes(startAttributes)
        .setCommandType(CommandType.COMMAND_TYPE_START_CHILD_WORKFLOW_EXECUTION)
        .build();
  }
}
