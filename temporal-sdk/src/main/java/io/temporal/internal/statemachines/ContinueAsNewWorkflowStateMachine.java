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
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.workflow.Functions;

final class ContinueAsNewWorkflowStateMachine
    extends EntityStateMachineInitialCommand<
        ContinueAsNewWorkflowStateMachine.State,
        ContinueAsNewWorkflowStateMachine.ExplicitEvent,
        ContinueAsNewWorkflowStateMachine> {

  private final ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewWorkflowAttributes;

  public static void newInstance(
      ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewWorkflowAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    new ContinueAsNewWorkflowStateMachine(
        continueAsNewWorkflowAttributes, commandSink, stateMachineSink);
  }

  private ContinueAsNewWorkflowStateMachine(
      ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewWorkflowAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.continueAsNewWorkflowAttributes = continueAsNewWorkflowAttributes;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE
  }

  enum State {
    CREATED,
    CONTINUE_AS_NEW_WORKFLOW_COMMAND_CREATED,
    CONTINUE_AS_NEW_WORKFLOW_COMMAND_RECORDED,
  }

  public static final StateMachineDefinition<
          State, ExplicitEvent, ContinueAsNewWorkflowStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition
              .<State, ExplicitEvent, ContinueAsNewWorkflowStateMachine>newInstance(
                  "ContinueAsNewWorkflow",
                  State.CREATED,
                  State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_RECORDED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
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

  private void createContinueAsNewWorkflowCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION)
            .setContinueAsNewWorkflowExecutionCommandAttributes(continueAsNewWorkflowAttributes)
            .build());
  }
}
