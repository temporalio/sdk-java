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
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.workflow.Functions;

final class ContinueAsNewWorkflowStateMachine
    extends EntityStateMachineInitialCommand<
        ContinueAsNewWorkflowStateMachine.State,
        ContinueAsNewWorkflowStateMachine.Action,
        ContinueAsNewWorkflowStateMachine> {

  private final ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewWorkflowAttributes;

  public static void newInstance(
      ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewWorkflowAttributes,
      Functions.Proc1<NewCommand> commandSink) {
    new ContinueAsNewWorkflowStateMachine(continueAsNewWorkflowAttributes, commandSink);
  }

  private ContinueAsNewWorkflowStateMachine(
      ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewWorkflowAttributes,
      Functions.Proc1<NewCommand> commandSink) {
    super(newStateMachine(), commandSink);
    this.continueAsNewWorkflowAttributes = continueAsNewWorkflowAttributes;
    action(Action.SCHEDULE);
  }

  enum Action {
    SCHEDULE
  }

  enum State {
    CREATED,
    CONTINUE_AS_NEW_WORKFLOW_COMMAND_CREATED,
    CONTINUE_AS_NEW_WORKFLOW_COMMAND_RECORDED,
  }

  private static StateMachine<State, Action, ContinueAsNewWorkflowStateMachine> newStateMachine() {
    return StateMachine.<State, Action, ContinueAsNewWorkflowStateMachine>newInstance(
            "ContinueAsNewWorkflow", State.CREATED, State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_RECORDED)
        .add(
            State.CREATED,
            Action.SCHEDULE,
            State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_CREATED,
            ContinueAsNewWorkflowStateMachine::createContinueAsNewWorkflowCommand)
        .add(
            State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_CREATED,
            CommandType.COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION,
            State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_CREATED)
        .add(
            State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_CREATED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW,
            State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_RECORDED);
  }

  private void createContinueAsNewWorkflowCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION)
            .setContinueAsNewWorkflowExecutionCommandAttributes(continueAsNewWorkflowAttributes)
            .build());
  }

  public static String asPlantUMLStateDiagram() {
    return newStateMachine().asPlantUMLStateDiagram();
  }
}
