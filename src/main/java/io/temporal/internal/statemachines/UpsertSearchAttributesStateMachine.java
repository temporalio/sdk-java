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
import io.temporal.api.command.v1.UpsertWorkflowSearchAttributesCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.workflow.Functions;

final class UpsertSearchAttributesStateMachine
    extends EntityStateMachineInitialCommand<
        UpsertSearchAttributesStateMachine.State,
        UpsertSearchAttributesStateMachine.Action,
        UpsertSearchAttributesStateMachine> {

  private final UpsertWorkflowSearchAttributesCommandAttributes upsertAttributes;

  public static void newInstance(
      UpsertWorkflowSearchAttributesCommandAttributes upsertAttributes,
      Functions.Proc1<NewCommand> commandSink) {
    new UpsertSearchAttributesStateMachine(upsertAttributes, commandSink);
  }

  private UpsertSearchAttributesStateMachine(
      UpsertWorkflowSearchAttributesCommandAttributes upsertAttributes,
      Functions.Proc1<NewCommand> commandSink) {
    super(newStateMachine(), commandSink);
    this.upsertAttributes = upsertAttributes;
    action(Action.SCHEDULE);
  }

  enum Action {
    SCHEDULE
  }

  enum State {
    CREATED,
    UPSERT_COMMAND_CREATED,
    UPSERT_COMMAND_RECORDED,
  }

  private static StateMachine<State, Action, UpsertSearchAttributesStateMachine> newStateMachine() {
    return StateMachine.<State, Action, UpsertSearchAttributesStateMachine>newInstance(
            "UpsertSearchAttributes", State.CREATED, State.UPSERT_COMMAND_RECORDED)
        .add(
            State.CREATED,
            Action.SCHEDULE,
            State.UPSERT_COMMAND_CREATED,
            UpsertSearchAttributesStateMachine::createUpsertCommand)
        .add(
            State.UPSERT_COMMAND_CREATED,
            CommandType.COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES,
            State.UPSERT_COMMAND_CREATED)
        .add(
            State.UPSERT_COMMAND_CREATED,
            EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES,
            State.UPSERT_COMMAND_RECORDED);
  }

  private void createUpsertCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES)
            .setUpsertWorkflowSearchAttributesCommandAttributes(upsertAttributes)
            .build());
  }

  public static String asPlantUMLStateDiagram() {
    return newStateMachine().asPlantUMLStateDiagram();
  }
}
