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

import io.temporal.api.command.v1.CancelWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.Command;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.workflow.Functions;

final class CancelWorkflowStateMachine
    extends EntityStateMachineInitialCommand<
        CancelWorkflowStateMachine.State,
        CancelWorkflowStateMachine.ExplicitEvent,
        CancelWorkflowStateMachine> {

  private final CancelWorkflowExecutionCommandAttributes cancelWorkflowAttributes;

  public static void newInstance(
      CancelWorkflowExecutionCommandAttributes cancelWorkflowAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    new CancelWorkflowStateMachine(cancelWorkflowAttributes, commandSink, stateMachineSink);
  }

  private CancelWorkflowStateMachine(
      CancelWorkflowExecutionCommandAttributes cancelWorkflowAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.cancelWorkflowAttributes = cancelWorkflowAttributes;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE
  }

  enum State {
    CREATED,
    CANCEL_WORKFLOW_COMMAND_CREATED,
    CANCEL_WORKFLOW_COMMAND_RECORDED,
  }

  public static final StateMachineDefinition<State, ExplicitEvent, CancelWorkflowStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, CancelWorkflowStateMachine>newInstance(
                  "CancelWorkflow", State.CREATED, State.CANCEL_WORKFLOW_COMMAND_RECORDED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.CANCEL_WORKFLOW_COMMAND_CREATED,
                  CancelWorkflowStateMachine::createCancelWorkflowCommand)
              .add(
                  State.CANCEL_WORKFLOW_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION,
                  State.CANCEL_WORKFLOW_COMMAND_CREATED)
              .add(
                  State.CANCEL_WORKFLOW_COMMAND_CREATED,
                  EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED,
                  State.CANCEL_WORKFLOW_COMMAND_RECORDED);

  private void createCancelWorkflowCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_CANCEL_WORKFLOW_EXECUTION)
            .setCancelWorkflowExecutionCommandAttributes(cancelWorkflowAttributes)
            .build());
  }
}
