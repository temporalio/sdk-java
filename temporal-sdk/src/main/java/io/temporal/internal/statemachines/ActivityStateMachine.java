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

import io.temporal.activity.ActivityCancellationType;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RequestCancelActivityTaskCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
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
import io.temporal.workflow.Functions;
import java.util.Optional;
import javax.annotation.Nonnull;

final class ActivityStateMachine
    extends EntityStateMachineInitialCommand<
        ActivityStateMachine.State, ActivityStateMachine.ExplicitEvent, ActivityStateMachine> {

  static final String ACTIVITY_FAILED_MESSAGE = "Activity task failed";

  static final String ACTIVITY_TIMED_OUT_MESSAGE = "Activity task timed out";

  static final String ACTIVITY_CANCELED_MESSAGE = "Activity canceled";

  private static final String JAVA_SDK = "JavaSDK";

  private final String activityId;
  private final ActivityType activityType;
  private final ActivityCancellationType cancellationType;

  private final Functions.Proc2<Optional<Payloads>, FailureResult> completionCallback;

  private ExecuteActivityParameters parameters;

  private long startedCommandEventId;

  enum ExplicitEvent {
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
  }

  public static final StateMachineDefinition<State, ExplicitEvent, ActivityStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, ActivityStateMachine>newInstance(
                  "Activity",
                  State.CREATED,
                  State.COMPLETED,
                  State.FAILED,
                  State.TIMED_OUT,
                  State.CANCELED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.SCHEDULE_COMMAND_CREATED,
                  ActivityStateMachine::createScheduleActivityTaskCommand)
              .add(
                  State.SCHEDULE_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK,
                  State.SCHEDULE_COMMAND_CREATED)
              .add(
                  State.SCHEDULE_COMMAND_CREATED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
                  State.SCHEDULED_EVENT_RECORDED,
                  ActivityStateMachine::setInitialCommandEventId)
              .add(
                  State.SCHEDULE_COMMAND_CREATED,
                  ExplicitEvent.CANCEL,
                  State.CANCELED,
                  ActivityStateMachine::cancelCommandNotifyCanceledImmediately)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
                  State.STARTED,
                  ActivityStateMachine::setStartedCommandEventId)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
                  State.TIMED_OUT,
                  ActivityStateMachine::notifyTimedOut)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  ExplicitEvent.CANCEL,
                  State.SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  ActivityStateMachine::createRequestCancelActivityTaskCommand)
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
                  State.STARTED,
                  ExplicitEvent.CANCEL,
                  State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  ActivityStateMachine::createRequestCancelActivityTaskCommand)
              .add(
                  State.SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK,
                  State.SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  ActivityStateMachine::notifyCanceledIfTryCancelImmediately)
              .add(
                  State.SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
                  State.SCHEDULED_ACTIVITY_CANCEL_EVENT_RECORDED)
              /*
              These state transitions are not possible.
              It looks like it is valid when an event, handling of which requests activity
              cancellation, precedes EVENT_TYPE_ACTIVITY_TASK_STARTED event.
              But as all code execution happens in the event loop the STARTED event is
              applied to the state machine (as it is done for all command events before
              the event loop invocation) before the cancellation request.
              .add(
                  State.SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
                  State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED)
               This one is not possible for similar reason. The timeout is delivered
               before the event loop execution.
              .add(
                  State.SCHEDULED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
                  State.TIMED_OUT,
                  ActivityStateMachine::cancelCommandNotifyTimedOut)
                   */
              .add(
                  State.SCHEDULED_ACTIVITY_CANCEL_EVENT_RECORDED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED,
                  State.CANCELED,
                  ActivityStateMachine::notifyCanceledFromEvent)
              .add(
                  State.SCHEDULED_ACTIVITY_CANCEL_EVENT_RECORDED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
                  State.STARTED_ACTIVITY_CANCEL_EVENT_RECORDED)
              .add(
                  State.SCHEDULED_ACTIVITY_CANCEL_EVENT_RECORDED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
                  State.TIMED_OUT,
                  ActivityStateMachine::notifyTimedOut)
              .add(
                  State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK,
                  State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED)
              .add(
                  State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
                  State.STARTED_ACTIVITY_CANCEL_EVENT_RECORDED,
                  ActivityStateMachine::notifyCanceledIfTryCancelFromEvent)
              /*
              These state transitions are not possible.
              It looks like it is valid when an event, handling of which requests activity
              cancellation, precedes EVENT_TYPE_ACTIVITY_TASK_[COMPLETED|FAILED|TIMED_OUT] event.
              But as all code execution happens in the event loop the completion event is
              applied to the state machine (as it is done for all command events before
              the event loop invocation) before the cancellation request.
              .add(
                  State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED,
                  State.COMPLETED,
                  ActivityStateMachine::cancelCommandNotifyCompleted)
              .add(
                  State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED,
                  State.FAILED,
                  ActivityStateMachine::cancelCommandNotifyFailed)
              .add(
                  State.STARTED_ACTIVITY_CANCEL_COMMAND_CREATED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
                  State.TIMED_OUT,
                  ActivityStateMachine::cancelCommandNotifyTimedOut)
                   */
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
                  EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
                  State.TIMED_OUT,
                  ActivityStateMachine::notifyTimedOut)
              .add(
                  State.STARTED_ACTIVITY_CANCEL_EVENT_RECORDED,
                  EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED,
                  State.CANCELED,
                  ActivityStateMachine::notifyCancellationFromEvent);

  /**
   * @param parameters attributes used to schedule an activity
   * @param completionCallback one of ActivityTaskCompletedEvent, ActivityTaskFailedEvent,
   *     ActivityTaskTimedOutEvent, ActivityTaskCanceledEvents
   * @param commandSink sink to send commands
   * @return an instance of ActivityCommands
   */
  public static ActivityStateMachine newInstance(
      ExecuteActivityParameters parameters,
      Functions.Proc2<Optional<Payloads>, FailureResult> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    return new ActivityStateMachine(parameters, completionCallback, commandSink, stateMachineSink);
  }

  private ActivityStateMachine(
      ExecuteActivityParameters parameters,
      Functions.Proc2<Optional<Payloads>, FailureResult> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.parameters = parameters;
    ScheduleActivityTaskCommandAttributes.Builder scheduleAttr = parameters.getAttributes();
    this.activityId = scheduleAttr.getActivityId();
    this.activityType = scheduleAttr.getActivityType();
    this.cancellationType = parameters.getCancellationType();
    this.completionCallback = completionCallback;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  public void createScheduleActivityTaskCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK)
            .setScheduleActivityTaskCommandAttributes(parameters.getAttributes())
            .build());
  }

  private void setStartedCommandEventId() {
    startedCommandEventId = currentEvent.getEventId();
  }

  public void cancel() {
    if (cancellationType == ActivityCancellationType.ABANDON) {
      notifyCanceled(false);
    } else if (!isFinalState()) {
      explicitEvent(ExplicitEvent.CANCEL);
    }
  }

  // *Immediately versions don't wait for a matching command, underlying callback will trigger an
  // event loop so the workflow can make progress, because promise gets filled.

  /**
   * {@link CommandType#COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK} command is not yet left to the server.
   * Cancel it in place and immediately notify the workflow code.
   */
  private void cancelCommandNotifyCanceledImmediately() {
    cancelCommand();
    // TODO With {@link ActivityCancellationType#ABANDON} we shouldn't even get here as it gets
    //  handled in #cancel.
    //  It's a code path for TRY_CANCEL and WAIT_CANCELLATION_COMPLETED only.
    //  Was the original design to cancel a not-yet-sent
    //  {@link CommandType#COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK} in case of ABANDON too?
    if (cancellationType != ActivityCancellationType.ABANDON) {
      notifyCanceled(false);
    }
  }

  /**
   * Workflow code doesn't need to wait for the cancellation event if {@link
   * ActivityCancellationType#TRY_CANCEL}, immediately notify the workflow code.
   */
  private void notifyCanceledIfTryCancelImmediately() {
    if (cancellationType == ActivityCancellationType.TRY_CANCEL) {
      notifyCanceled(false);
    }
  }

  // *FromEvent versions will not trigger event loop as they need to wait for all events to be
  // applied before and there will be WorkflowTaskStarted to trigger the event loop.

  /**
   * if {@link EventType#EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED} is observed, notify the workflow
   * code if {@link ActivityCancellationType#TRY_CANCEL}, this mode doesn't need a confirmation of
   * cancellation.
   */
  private void notifyCanceledIfTryCancelFromEvent() {
    if (cancellationType == ActivityCancellationType.TRY_CANCEL) {
      notifyCanceled(true);
    }
  }

  /**
   * Notify workflow code of the cancellation from the {@link
   * EventType#EVENT_TYPE_ACTIVITY_TASK_CANCELED} event.
   *
   * <p>There is no harm in notifying {@link ActivityCancellationType#TRY_CANCEL} again, but it
   * should not be needed as it should be already done by {@link
   * #notifyCanceledIfTryCancelFromEvent} as there should be no {@link
   * EventType#EVENT_TYPE_ACTIVITY_TASK_CANCELED} without {@link
   * EventType#EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED}.
   */
  private void notifyCanceledFromEvent() {
    notifyCanceled(true);
  }

  private void notifyCanceled(boolean fromEvent) {
    Failure canceledFailure =
        Failure.newBuilder()
            .setSource(JAVA_SDK)
            .setCanceledFailureInfo(CanceledFailureInfo.getDefaultInstance())
            .build();
    ActivityFailureInfo activityFailureInfo =
        ActivityFailureInfo.newBuilder()
            .setActivityId(activityId)
            .setActivityType(activityType)
            .setIdentity("workflow")
            .setScheduledEventId(getInitialCommandEventId())
            .setStartedEventId(startedCommandEventId)
            .build();
    Failure failure =
        Failure.newBuilder()
            .setActivityFailureInfo(activityFailureInfo)
            .setCause(canceledFailure)
            .setMessage(ACTIVITY_CANCELED_MESSAGE)
            .build();
    completionCallback.apply(Optional.empty(), new FailureResult(failure, fromEvent));
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
    ActivityFailureInfo failureInfo =
        ActivityFailureInfo.newBuilder()
            .setActivityId(activityId)
            .setActivityType(activityType)
            .setIdentity(failed.getIdentity())
            .setRetryState(failed.getRetryState())
            .setScheduledEventId(failed.getScheduledEventId())
            .setStartedEventId(failed.getStartedEventId())
            .build();
    Failure failure =
        Failure.newBuilder()
            .setActivityFailureInfo(failureInfo)
            .setCause(failed.getFailure())
            .setMessage(ACTIVITY_FAILED_MESSAGE)
            .build();
    completionCallback.apply(Optional.empty(), new FailureResult(failure, true));
  }

  private void notifyTimedOut() {
    ActivityTaskTimedOutEventAttributes timedOut =
        currentEvent.getActivityTaskTimedOutEventAttributes();

    ActivityFailureInfo failureInfo =
        ActivityFailureInfo.newBuilder()
            .setActivityId(activityId)
            .setActivityType(activityType)
            .setRetryState(timedOut.getRetryState())
            .setScheduledEventId(timedOut.getScheduledEventId())
            .setStartedEventId(timedOut.getStartedEventId())
            .build();
    Failure failure =
        Failure.newBuilder()
            .setActivityFailureInfo(failureInfo)
            .setCause(timedOut.getFailure())
            .setMessage(ACTIVITY_TIMED_OUT_MESSAGE)
            .build();
    completionCallback.apply(Optional.empty(), new FailureResult(failure, true));
  }

  private void notifyCancellationFromEvent() {
    if (cancellationType == ActivityCancellationType.WAIT_CANCELLATION_COMPLETED) {
      ActivityTaskCanceledEventAttributes canceledAttr =
          currentEvent.getActivityTaskCanceledEventAttributes();
      Failure canceledFailure =
          Failure.newBuilder()
              .setSource(JAVA_SDK)
              .setCanceledFailureInfo(
                  CanceledFailureInfo.newBuilder().setDetails(canceledAttr.getDetails()))
              .build();

      ActivityFailureInfo failureInfo =
          ActivityFailureInfo.newBuilder()
              .setActivityId(activityId)
              .setActivityType(activityType)
              .setScheduledEventId(canceledAttr.getScheduledEventId())
              .setStartedEventId(canceledAttr.getStartedEventId())
              .build();
      Failure failure =
          Failure.newBuilder()
              .setActivityFailureInfo(failureInfo)
              .setCause(canceledFailure)
              .setMessage(ACTIVITY_CANCELED_MESSAGE)
              .build();

      completionCallback.apply(Optional.empty(), new FailureResult(failure, true));
    }
  }

  private void createRequestCancelActivityTaskCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_REQUEST_CANCEL_ACTIVITY_TASK)
            .setRequestCancelActivityTaskCommandAttributes(
                RequestCancelActivityTaskCommandAttributes.newBuilder()
                    .setScheduledEventId(getInitialCommandEventId()))
            .build());
    parameters = null; // avoid retaining large input for the duration of the activity
  }

  public static class FailureResult {
    private final @Nonnull Failure failure;
    private final boolean fromEvent;

    public FailureResult(@Nonnull Failure failure, boolean fromEvent) {
      this.failure = failure;
      this.fromEvent = fromEvent;
    }

    @Nonnull
    public Failure getFailure() {
      return failure;
    }

    /**
     * @return true if this failure is created from the event during event <-> command matching
     *     phase.
     */
    public boolean isFromEvent() {
      return fromEvent;
    }
  }
}
