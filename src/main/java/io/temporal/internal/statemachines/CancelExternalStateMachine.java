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
import io.temporal.api.command.v1.RequestCancelExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.workflow.CancelExternalWorkflowException;
import io.temporal.workflow.Functions;

final class CancelExternalStateMachine
    extends EntityStateMachineInitialCommand<
        CancelExternalStateMachine.State,
        CancelExternalStateMachine.Action,
        CancelExternalStateMachine> {

  private final RequestCancelExternalWorkflowExecutionCommandAttributes requestCancelAttributes;

  private final Functions.Proc2<Void, RuntimeException> completionCallback;

  /**
   * @param attributes attributes to use to cancel external worklfow
   * @param completionCallback one of ExternalWorkflowExecutionCancelRequestedEvent,
   *     RequestCancelExternalWorkflowExecutionFailedEvent
   * @param commandSink sink to send commands
   */
  public static void newInstance(
      RequestCancelExternalWorkflowExecutionCommandAttributes attributes,
      Functions.Proc2<Void, RuntimeException> completionCallback,
      Functions.Proc1<NewCommand> commandSink) {
    new CancelExternalStateMachine(attributes, completionCallback, commandSink);
  }

  private CancelExternalStateMachine(
      RequestCancelExternalWorkflowExecutionCommandAttributes requestCancelAttributes,
      Functions.Proc2<Void, RuntimeException> completionCallback,
      Functions.Proc1<NewCommand> commandSink) {
    super(newStateMachine(), commandSink);
    this.requestCancelAttributes = requestCancelAttributes;
    this.completionCallback = completionCallback;
    action(Action.SCHEDULE);
  }

  enum Action {
    SCHEDULE
  }

  enum State {
    CREATED,
    REQUEST_CANCEL_EXTERNAL_COMMAND_CREATED,
    REQUEST_CANCEL_EXTERNAL_COMMAND_RECORDED,
    CANCEL_REQUESTED,
    REQUEST_CANCEL_FAILED,
  }

  private static StateMachine<State, Action, CancelExternalStateMachine> newStateMachine() {
    return StateMachine.<State, Action, CancelExternalStateMachine>newInstance(
            "CancelExternal", State.CREATED, State.CANCEL_REQUESTED, State.REQUEST_CANCEL_FAILED)
        .add(
            State.CREATED,
            Action.SCHEDULE,
            State.REQUEST_CANCEL_EXTERNAL_COMMAND_CREATED,
            CancelExternalStateMachine::createCancelExternalCommand)
        .add(
            State.REQUEST_CANCEL_EXTERNAL_COMMAND_CREATED,
            CommandType.COMMAND_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION,
            State.REQUEST_CANCEL_EXTERNAL_COMMAND_CREATED)
        .add(
            State.REQUEST_CANCEL_EXTERNAL_COMMAND_CREATED,
            EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED,
            State.REQUEST_CANCEL_EXTERNAL_COMMAND_RECORDED)
        .add(
            State.REQUEST_CANCEL_EXTERNAL_COMMAND_RECORDED,
            EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED,
            State.CANCEL_REQUESTED,
            CancelExternalStateMachine::notifyCompleted)
        .add(
            State.REQUEST_CANCEL_EXTERNAL_COMMAND_RECORDED,
            EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED,
            State.REQUEST_CANCEL_FAILED,
            CancelExternalStateMachine::notifyFailed);
  }

  private void createCancelExternalCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION)
            .setRequestCancelExternalWorkflowExecutionCommandAttributes(requestCancelAttributes)
            .build());
  }

  private void notifyCompleted() {
    completionCallback.apply(null, null);
  }

  private void notifyFailed() {
    WorkflowExecution execution =
        WorkflowExecution.newBuilder()
            .setWorkflowId(requestCancelAttributes.getWorkflowId())
            .setRunId(requestCancelAttributes.getRunId())
            .build();
    completionCallback.apply(null, new CancelExternalWorkflowException(execution, "", null));
  }

  public static String asPlantUMLStateDiagram() {
    return newStateMachine().asPlantUMLStateDiagram();
  }
}
