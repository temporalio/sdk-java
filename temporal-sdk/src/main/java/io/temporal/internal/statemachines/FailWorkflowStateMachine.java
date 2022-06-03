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
import io.temporal.api.command.v1.FailWorkflowExecutionCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.workflow.Functions;

final class FailWorkflowStateMachine
    extends EntityStateMachineInitialCommand<
        FailWorkflowStateMachine.State,
        FailWorkflowStateMachine.ExplicitEvent,
        FailWorkflowStateMachine> {

  private final FailWorkflowExecutionCommandAttributes failWorkflowAttributes;

  public static void newInstance(
      Failure failure,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    FailWorkflowExecutionCommandAttributes attributes =
        FailWorkflowExecutionCommandAttributes.newBuilder().setFailure(failure).build();
    new FailWorkflowStateMachine(attributes, commandSink, stateMachineSink);
  }

  private FailWorkflowStateMachine(
      FailWorkflowExecutionCommandAttributes failWorkflowAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.failWorkflowAttributes = failWorkflowAttributes;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE
  }

  enum State {
    CREATED,
    FAIL_WORKFLOW_COMMAND_CREATED,
    FAIL_WORKFLOW_COMMAND_RECORDED,
  }

  public static final StateMachineDefinition<State, ExplicitEvent, FailWorkflowStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, FailWorkflowStateMachine>newInstance(
                  "FailWorkflow", State.CREATED, State.FAIL_WORKFLOW_COMMAND_RECORDED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
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

  private void createFailWorkflowCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_FAIL_WORKFLOW_EXECUTION)
            .setFailWorkflowExecutionCommandAttributes(failWorkflowAttributes)
            .build());
  }
}
