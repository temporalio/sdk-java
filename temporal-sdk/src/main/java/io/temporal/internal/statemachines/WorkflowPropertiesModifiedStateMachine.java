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
import io.temporal.api.command.v1.ModifyWorkflowPropertiesCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.workflow.Functions;

final class WorkflowPropertiesModifiedStateMachine
    extends EntityStateMachineInitialCommand<
        WorkflowPropertiesModifiedStateMachine.State,
        WorkflowPropertiesModifiedStateMachine.ExplicitEvent,
        WorkflowPropertiesModifiedStateMachine> {

  private ModifyWorkflowPropertiesCommandAttributes modifiedPropertiesAttributes;

  public static void newInstance(
      ModifyWorkflowPropertiesCommandAttributes modifiedPropertiesAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    new WorkflowPropertiesModifiedStateMachine(
        modifiedPropertiesAttributes, commandSink, stateMachineSink);
  }

  private WorkflowPropertiesModifiedStateMachine(
      ModifyWorkflowPropertiesCommandAttributes modifiedPropertiesAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.modifiedPropertiesAttributes = modifiedPropertiesAttributes;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE
  }

  enum State {
    CREATED,
    MODIFY_COMMAND_CREATED,
    MODIFY_COMMAND_RECORDED,
  }

  public static final StateMachineDefinition<
          State, ExplicitEvent, WorkflowPropertiesModifiedStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition
              .<State, ExplicitEvent, WorkflowPropertiesModifiedStateMachine>newInstance(
                  "WorkflowPropertiesModified", State.CREATED, State.MODIFY_COMMAND_RECORDED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.MODIFY_COMMAND_CREATED,
                  WorkflowPropertiesModifiedStateMachine::createModifyCommand)
              .add(
                  State.MODIFY_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_MODIFY_WORKFLOW_PROPERTIES,
                  State.MODIFY_COMMAND_CREATED)
              .add(
                  State.MODIFY_COMMAND_CREATED,
                  EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED,
                  State.MODIFY_COMMAND_RECORDED);

  private void createModifyCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_MODIFY_WORKFLOW_PROPERTIES)
            .setModifyWorkflowPropertiesCommandAttributes(modifiedPropertiesAttributes)
            .build());
    modifiedPropertiesAttributes = null;
  }
}
