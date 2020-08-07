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
import io.temporal.api.command.v1.FailWorkflowExecutionCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.workflow.Functions;

final class FailWorkflowStateMachine
    extends EntityStateMachineInitialCommand<
        FailWorkflowStateMachine.State, FailWorkflowStateMachine.Action, FailWorkflowStateMachine> {

  private final FailWorkflowExecutionCommandAttributes failWorkflowAttributes;

  public static void newInstance(Failure failure, Functions.Proc1<NewCommand> commandSink) {
    FailWorkflowExecutionCommandAttributes attributes =
        FailWorkflowExecutionCommandAttributes.newBuilder().setFailure(failure).build();
    new FailWorkflowStateMachine(attributes, commandSink);
  }

  private FailWorkflowStateMachine(
      FailWorkflowExecutionCommandAttributes failWorkflowAttributes,
      Functions.Proc1<NewCommand> commandSink) {
    super(newStateMachine(), commandSink);
    this.failWorkflowAttributes = failWorkflowAttributes;
    action(Action.SCHEDULE);
  }

  enum Action {
    SCHEDULE
  }

  enum State {
    CREATED,
    FAIL_WORKFLOW_COMMAND_CREATED,
    FAIL_WORKFLOW_COMMAND_RECORDED,
  }

  private static StateMachine<State, Action, FailWorkflowStateMachine> newStateMachine() {
    return StateMachine.<State, Action, FailWorkflowStateMachine>newInstance(
            "FailWorkflow", State.CREATED, State.FAIL_WORKFLOW_COMMAND_RECORDED)
        .add(
            State.CREATED,
            Action.SCHEDULE,
            State.FAIL_WORKFLOW_COMMAND_CREATED,
            FailWorkflowStateMachine::createFailWorkflowCommand)
        .add(
            State.FAIL_WORKFLOW_COMMAND_CREATED,
            CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION,
            State.FAIL_WORKFLOW_COMMAND_CREATED)
        .add(
            State.FAIL_WORKFLOW_COMMAND_CREATED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED,
            State.FAIL_WORKFLOW_COMMAND_RECORDED);
  }

  private void createFailWorkflowCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION)
            .setFailWorkflowExecutionCommandAttributes(failWorkflowAttributes)
            .build());
  }

  public static String asPlantUMLStateDiagram() {
    return newStateMachine().asPlantUMLStateDiagram();
  }
}
