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

import static io.temporal.failure.FailureConverter.JAVA_SDK;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RequestCancelActivityTaskCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.ActivityFailureInfo;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.ActivityTaskCanceledEventAttributes;
import io.temporal.api.history.v1.ActivityTaskCompletedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskFailedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskTimedOutEventAttributes;
import io.temporal.internal.replay.ExecuteActivityParameters;
import io.temporal.workflow.Functions;
import java.util.Optional;

final class ActivityStateMachine
    extends EntityStateMachineInitialCommand<
        ActivityStateMachine.State, ActivityStateMachine.Action, ActivityStateMachine> {

  enum Action {
    SCHEDULE,
    CANCEL
  }

  enum State {
    CREATED,
    SCHEDULE_COMMAND_CREATED,
    SCHEDULED_EVENT_RECORDED,
    STARTED,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELED,
    SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED,
    SCHEDULED_ACTIVITY_CANCEL_EVENT_RECORDED,
    STARTED_ACTIVITY_CANCEL_COMMAND_CREATED,
    STARTED_ACTIVITY_CANCEL_EVENT_RECORDED,
    COMPLETED_CANCEL_REQUESTED,
    FAILED_CANCEL_REQUESTED,
    CANCELED_CANCEL_REQUESTED,
  }

  private static StateMachine<State, Action, ActivityStateMachine> newStateMachine() {
    return StateMachine.<State, Action, ActivityStateMachine>newInstance(
            "Activity",
            State.CREATED,
            State.COMPLETED,
            State.FAILED,
            State.TIMED_OUT,
            State.CANCELED)
        .add(
            State.CREATED,
            Action.SCHEDULE,
            State.SCHEDULE_COMMAND_CREATED,
            ActivityStateMachine::createScheduleActivityTaskCommand)
        .add(
            State.SCHEDULE_COMMAND_CREATED,
            CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK,
            State.SCHEDULE_COMMAND_CREATED)
        .add(
            State.SCHEDULE_COMMAND_CREATED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            State.SCHEDULED_EVENT_RECORDED)
        .add(
            State.SCHEDULE_COMMAND_CREATED,
            Action.CANCEL,
            State.CANCELED,
            ActivityStateMachine::cancelScheduleCommand)
        .add(
            State.SCHEDULED_EVENT_RECORDED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            State.STARTED)
        .add(
            State.SCHEDULED_EVENT_RECORDED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
            State.TIMED_OUT,
            ActivityStateMachine::notifyTimedOut)
        .add(
            State.STARTED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED,
            State.COMPLETED,
            ActivityStateMachine::notifyCompleted)
        .add(
            State.STARTED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED,
            State.FAILED,
            ActivityStateMachine::notifyFailed)
        .add(
            State.STARTED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
            State.TIMED_OUT,
            ActivityStateMachine::notifyTimedOut)
        .add(
            State.SCHEDULED_EVENT_RECORDED,
            Action.CANCEL,
            State.SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED,
            ActivityStateMachine::createRequestCancelActivityTaskCommand)
        .add(
            State.SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            State.SCHEDULED_ACTIVITY_CANCEL_EVENT_RECORDED,
            ActivityStateMachine::notifyCanceledIfTryCancel)
        .add(
            State.SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED,
            CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK,
            State.SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED)
        .add(
            State.SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED)
        .add(
            State.SCHEDULED_ACTIVITY_CANCEL_EVENT_RECORDED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED,
            State.CANCELED,
            ActivityStateMachine::notifyCanceled)
        .add(
            State.SCHEDULED_ACTIVITY_CANCEL_EVENT_RECORDED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            State.STARTED_ACTIVITY_CANCEL_EVENT_RECORDED)
        .add(
            State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            State.STARTED_ACTIVITY_CANCEL_EVENT_RECORDED,
            ActivityStateMachine::notifyCanceledIfTryCancel)
        .add(
            State.STARTED,
            Action.CANCEL,
            State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED,
            ActivityStateMachine::createRequestCancelActivityTaskCommand)
        .add(
            State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED,
            State.COMPLETED,
            ActivityStateMachine::cancelScheduleCommandNotifyCompleted)
        .add(
            State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED,
            State.FAILED,
            ActivityStateMachine::cancelScheduleCommandNotifyFailed)
        .add(
            State.STARTED_ACTIVITY_CANCEL_EVENT_RECORDED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED,
            State.FAILED,
            ActivityStateMachine::notifyFailed)
        .add(
            State.STARTED_ACTIVITY_CANCEL_EVENT_RECORDED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED,
            State.COMPLETED,
            ActivityStateMachine::notifyCompleted)
        .add(
            State.STARTED_ACTIVITY_CANCEL_EVENT_RECORDED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED,
            State.CANCELED,
            ActivityStateMachine::notifyCancellationFromEvent);
  }

  private final ExecuteActivityParameters parameters;

  private final Functions.Proc2<Optional<Payloads>, Failure> completionCallback;

  /**
   * @param parameters attributes used to schedule an activity
   * @param completionCallback one of ActivityTaskCompletedEvent, ActivityTaskFailedEvent,
   *     ActivityTaskTimedOutEvent, ActivityTaskCanceledEvents
   * @param commandSink sink to send commands
   * @return an instance of ActivityCommands
   */
  public static ActivityStateMachine newInstance(
      ExecuteActivityParameters parameters,
      Functions.Proc2<Optional<Payloads>, Failure> completionCallback,
      Functions.Proc1<NewCommand> commandSink) {
    return new ActivityStateMachine(parameters, completionCallback, commandSink);
  }

  private ActivityStateMachine(
      ExecuteActivityParameters parameters,
      Functions.Proc2<Optional<Payloads>, Failure> completionCallback,
      Functions.Proc1<NewCommand> commandSink) {
    super(newStateMachine(), commandSink);
    this.parameters = parameters;
    this.completionCallback = completionCallback;
    action(Action.SCHEDULE);
  }

  public void createScheduleActivityTaskCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK)
            .setScheduleActivityTaskCommandAttributes(parameters.getAttributes())
            .build());
  }

  public void cancel() {
    if (parameters.getCancellationType() == ActivityCancellationType.ABANDON) {
      notifyCanceled();
    } else {
      action(Action.CANCEL);
    }
  }

  private void cancelScheduleCommand() {
    cancelInitialCommand();
    if (parameters.getCancellationType() != ActivityCancellationType.ABANDON) {
      notifyCanceled();
    }
  }

  private void notifyCanceledIfTryCancel() {
    if (parameters.getCancellationType() == ActivityCancellationType.TRY_CANCEL) {
      notifyCanceled();
    }
  }

  private void notifyCanceled() {
    Failure canceledFailure =
        Failure.newBuilder()
            .setSource(JAVA_SDK)
            .setCanceledFailureInfo(CanceledFailureInfo.getDefaultInstance())
            .build();
    completionCallback.apply(Optional.empty(), canceledFailure);
  }

  private void notifyCompleted() {
    ActivityTaskCompletedEventAttributes completedAttr =
        currentEvent.getActivityTaskCompletedEventAttributes();
    Optional<Payloads> result =
        completedAttr.hasResult() ? Optional.of(completedAttr.getResult()) : Optional.empty();
    completionCallback.apply(result, null);
  }

  private void notifyFailed() {
    ActivityTaskFailedEventAttributes failed = currentEvent.getActivityTaskFailedEventAttributes();
    ScheduleActivityTaskCommandAttributes.Builder scheduleAttr = parameters.getAttributes();
    ActivityFailureInfo failureInfo =
        ActivityFailureInfo.newBuilder()
            .setActivityId(scheduleAttr.getActivityId())
            .setActivityType(scheduleAttr.getActivityType())
            .setIdentity(failed.getIdentity())
            .setRetryState(failed.getRetryState())
            .setScheduledEventId(failed.getScheduledEventId())
            .setStartedEventId(failed.getStartedEventId())
            .build();
    Failure failure =
        Failure.newBuilder()
            .setActivityFailureInfo(failureInfo)
            .setCause(failed.getFailure())
            .setMessage("Activity task failed")
            .build();
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyTimedOut() {
    ScheduleActivityTaskCommandAttributes.Builder scheduleAttr = parameters.getAttributes();

    ActivityTaskTimedOutEventAttributes timedOut =
        currentEvent.getActivityTaskTimedOutEventAttributes();

    ActivityFailureInfo failureInfo =
        ActivityFailureInfo.newBuilder()
            .setActivityId(scheduleAttr.getActivityId())
            .setActivityType(scheduleAttr.getActivityType())
            .setRetryState(timedOut.getRetryState())
            .setScheduledEventId(timedOut.getScheduledEventId())
            .setStartedEventId(timedOut.getStartedEventId())
            .build();
    Failure failure =
        Failure.newBuilder()
            .setActivityFailureInfo(failureInfo)
            .setCause(timedOut.getFailure())
            .setMessage("Activity task timedOut")
            .build();
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyCancellationFromEvent() {
    if (parameters.getCancellationType() == ActivityCancellationType.WAIT_CANCELLATION_COMPLETED) {
      ActivityTaskCanceledEventAttributes canceledAttr =
          currentEvent.getActivityTaskCanceledEventAttributes();
      Failure canceledFailure =
          Failure.newBuilder()
              .setSource(JAVA_SDK)
              .setCanceledFailureInfo(
                  CanceledFailureInfo.newBuilder().setDetails(canceledAttr.getDetails()))
              .build();
      completionCallback.apply(Optional.empty(), canceledFailure);
    }
  }

  private void cancelScheduleCommandNotifyCompleted() {
    cancelScheduleCommand();
    notifyCompleted();
  }

  private void cancelScheduleCommandNotifyFailed() {
    cancelScheduleCommand();
    notifyFailed();
  }

  private void createRequestCancelActivityTaskCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK)
            .setRequestCancelActivityTaskCommandAttributes(
                RequestCancelActivityTaskCommandAttributes.newBuilder()
                    .setScheduledEventId(getInitialCommandEventId()))
            .build());
  }

  public static String asPlantUMLStateDiagram() {
    return newStateMachine().asPlantUMLStateDiagram();
  }
}
