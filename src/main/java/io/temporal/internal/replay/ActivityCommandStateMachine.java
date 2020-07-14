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
import io.temporal.api.command.v1.RequestCancelActivityTaskCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.history.v1.HistoryEvent;

final class ActivityCommandStateMachine extends CommandStateMachineBase {

  private ScheduleActivityTaskCommandAttributes scheduleAttributes;
  private long scheduledEventId;

  public ActivityCommandStateMachine(
      CommandId id,
      ScheduleActivityTaskCommandAttributes scheduleAttributes,
      long scheduledEventId) {
    super(id);
    this.scheduleAttributes = scheduleAttributes;
    this.scheduledEventId = scheduledEventId;
  }

  /** Used for unit testing */
  ActivityCommandStateMachine(
      CommandId id,
      ScheduleActivityTaskCommandAttributes scheduleAttributes,
      DecisionState state,
      long scheduledEventId) {
    super(id, state);
    this.scheduleAttributes = scheduleAttributes;
    this.scheduledEventId = scheduledEventId;
  }

  @Override
  public Command getCommand() {
    switch (state) {
      case CREATED:
        return createScheduleActivityTaskCommand();
      case CANCELED_AFTER_INITIATED:
        return createRequestCancelActivityTaskCommand();
      default:
        return null;
    }
  }

  @Override
  public void handleWorkflowTaskStartedEvent() {
    switch (state) {
      case CANCELED_AFTER_INITIATED:
        stateHistory.add("handleWorkflowTaskStartedEvent");
        state = DecisionState.CANCELLATION_DECISION_SENT;
        stateHistory.add(state.toString());
        break;
      default:
        super.handleWorkflowTaskStartedEvent();
    }
  }

  @Override
  public void handleCancellationFailureEvent(HistoryEvent event) {
    switch (state) {
      case CANCELLATION_DECISION_SENT:
        stateHistory.add("handleCancellationFailureEvent");
        state = DecisionState.INITIATED;
        stateHistory.add(state.toString());
        break;
      default:
        super.handleCancellationFailureEvent(event);
    }
  }

  private Command createRequestCancelActivityTaskCommand() {
    return Command.newBuilder()
        .setRequestCancelActivityTaskCommandAttributes(
            RequestCancelActivityTaskCommandAttributes.newBuilder()
                .setScheduledEventId(scheduledEventId))
        .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK)
        .build();
  }

  private Command createScheduleActivityTaskCommand() {
    scheduledEventId = getId().getDecisionEventId();
    return Command.newBuilder()
        .setScheduleActivityTaskCommandAttributes(scheduleAttributes)
        .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK)
        .build();
  }
}
