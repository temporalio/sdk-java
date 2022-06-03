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
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.failure.v1.ActivityFailureInfo;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.FailureConverter;
import io.temporal.internal.replay.ExecuteLocalActivityParameters;
import io.temporal.internal.worker.ActivityTaskHandler;
import io.temporal.workflow.Functions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class LocalActivityStateMachine
    extends EntityStateMachineInitialCommand<
        LocalActivityStateMachine.State,
        LocalActivityStateMachine.ExplicitEvent,
        LocalActivityStateMachine> {

  static final String LOCAL_ACTIVITY_MARKER_NAME = "LocalActivity";
  static final String MARKER_ACTIVITY_ID_KEY = "activityId";
  static final String MARKER_ACTIVITY_TYPE_KEY = "type";
  static final String MARKER_ACTIVITY_INPUT_KEY = "input";
  static final String MARKER_ACTIVITY_RESULT_KEY = "result";
  static final String MARKER_TIME_KEY = "time";
  // Deprecated in favor of result. Still present for backwards compatibility.
  static final String MARKER_DATA_KEY = "data";

  private final DataConverter dataConverter = DataConverter.getDefaultInstance();

  private final Functions.Proc1<ExecuteLocalActivityParameters> localActivityRequestSink;
  private final Functions.Proc2<Optional<Payloads>, Failure> callback;

  private ExecuteLocalActivityParameters localActivityParameters;
  private final Functions.Func<Boolean> replaying;
  /** Accepts proposed current time. Returns accepted current time. */
  private final Functions.Func1<Long, Long> setCurrentTimeCallback;

  private final boolean hasRetryPolicy;
  private final String activityId;
  private final ActivityType activityType;

  /** Workflow timestamp when the LA state machine is initialized */
  private final long workflowTimeMillisWhenStarted;
  /**
   * System.nanoTime result at the moment of LA state machine initialization. May be used to
   * calculate elapsed time
   */
  private final long systemNanoTimeWhenStarted;

  private Failure failure;
  private ActivityTaskHandler.Result result;
  private Optional<Payloads> laResult;

  /**
   * Creates new local activity marker
   *
   * @param localActivityParameters used to produce side effect value. null if replaying.
   * @param callback returns side effect value or failure
   * @param commandSink callback to send commands to
   */
  public static LocalActivityStateMachine newInstance(
      Functions.Func<Boolean> replaying,
      Functions.Func1<Long, Long> setCurrentTimeCallback,
      ExecuteLocalActivityParameters localActivityParameters,
      Functions.Proc2<Optional<Payloads>, Failure> callback,
      Functions.Proc1<ExecuteLocalActivityParameters> localActivityRequestSink,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink,
      long workflowTimeMillisWhenStarted) {
    return new LocalActivityStateMachine(
        replaying,
        setCurrentTimeCallback,
        localActivityParameters,
        callback,
        localActivityRequestSink,
        commandSink,
        stateMachineSink,
        workflowTimeMillisWhenStarted,
        System.nanoTime());
  }

  private LocalActivityStateMachine(
      Functions.Func<Boolean> replaying,
      Functions.Func1<Long, Long> setCurrentTimeCallback,
      ExecuteLocalActivityParameters localActivityParameters,
      Functions.Proc2<Optional<Payloads>, Failure> callback,
      Functions.Proc1<ExecuteLocalActivityParameters> localActivityRequestSink,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink,
      long workflowTimeMillisWhenStarted,
      long systemNanoTimeWhenStarted) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.replaying = replaying;
    this.setCurrentTimeCallback = setCurrentTimeCallback;
    this.localActivityParameters = localActivityParameters;
    PollActivityTaskQueueResponse.Builder activityTask = localActivityParameters.getActivityTask();
    this.hasRetryPolicy = activityTask.hasRetryPolicy();
    this.activityId = activityTask.getActivityId();
    this.activityType = activityTask.getActivityType();
    this.localActivityRequestSink = localActivityRequestSink;
    this.callback = callback;
    this.workflowTimeMillisWhenStarted = workflowTimeMillisWhenStarted;
    this.systemNanoTimeWhenStarted = systemNanoTimeWhenStarted;
    explicitEvent(ExplicitEvent.CHECK_EXECUTION_STATE);
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    CHECK_EXECUTION_STATE,
    SCHEDULE,
    MARK_AS_SENT,
    HANDLE_RESULT,
    NON_REPLAY_WORKFLOW_TASK_STARTED
  }

  enum State {
    CREATED,
    REPLAYING,
    EXECUTING,
    REQUEST_PREPARED,
    REQUEST_SENT,
    RESULT_NOTIFIED,
    MARKER_COMMAND_CREATED,
    MARKER_COMMAND_RECORDED,
    WAITING_MARKER_EVENT,
    RESULT_NOTIFIED_REPLAYING
  }

  public static final StateMachineDefinition<State, ExplicitEvent, LocalActivityStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, LocalActivityStateMachine>newInstance(
                  "LocalActivity", State.CREATED, State.MARKER_COMMAND_RECORDED)
              .add(
                  State.CREATED,
                  ExplicitEvent.CHECK_EXECUTION_STATE,
                  new State[] {State.REPLAYING, State.EXECUTING},
                  LocalActivityStateMachine::getExecutionState)
              .add(
                  State.EXECUTING,
                  ExplicitEvent.SCHEDULE,
                  State.REQUEST_PREPARED,
                  LocalActivityStateMachine::sendRequest)
              .add(State.REQUEST_PREPARED, ExplicitEvent.MARK_AS_SENT, State.REQUEST_SENT)
              .add(
                  State.REQUEST_SENT,
                  ExplicitEvent.NON_REPLAY_WORKFLOW_TASK_STARTED,
                  State.REQUEST_SENT)
              .add(
                  State.REQUEST_SENT,
                  ExplicitEvent.HANDLE_RESULT,
                  State.MARKER_COMMAND_CREATED,
                  LocalActivityStateMachine::createMarker)
              .add(
                  State.MARKER_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_RECORD_MARKER,
                  State.RESULT_NOTIFIED,
                  LocalActivityStateMachine::notifyResultFromResponse)
              .add(
                  State.RESULT_NOTIFIED,
                  EventType.EVENT_TYPE_MARKER_RECORDED,
                  State.MARKER_COMMAND_RECORDED)
              .add(State.REPLAYING, ExplicitEvent.SCHEDULE, State.WAITING_MARKER_EVENT)
              .add(
                  State.WAITING_MARKER_EVENT,
                  EventType.EVENT_TYPE_MARKER_RECORDED,
                  State.MARKER_COMMAND_RECORDED,
                  LocalActivityStateMachine::notifyResultFromEvent)
              .add(
                  // This is to cover the following edge case:
                  // 1. WorkflowTaskStarted
                  // 2. Local activity scheduled
                  // 3. Local activity taken and started execution
                  // 4. Forced workflow task is started
                  // 5. Workflow task fails or worker crashes
                  // When replaying the above sequence without this state transition the local
                  // activity
                  // scheduled at step 2 is going to be lost.
                  State.WAITING_MARKER_EVENT,
                  ExplicitEvent.NON_REPLAY_WORKFLOW_TASK_STARTED,
                  State.REQUEST_PREPARED,
                  LocalActivityStateMachine::sendRequest);

  State getExecutionState() {
    return replaying.apply() ? State.REPLAYING : State.EXECUTING;
  }

  public void cancel() {
    // TODO(maxim): Cancellation of local activity.
    //    if (!isFinalState()) {
    //      explicitEvent(ExplicitEvent.CANCEL);
    //    }
  }

  public void sendRequest() {
    localActivityRequestSink.apply(localActivityParameters);
    if (localActivityParameters.isDoNotIncludeArgumentsIntoMarker()) {
      // avoid retaining parameters for the duration of activity execution
      localActivityParameters = null;
    }
  }

  public void markAsSent() {
    explicitEvent(ExplicitEvent.MARK_AS_SENT);
  }

  public void handleCompletion(ActivityTaskHandler.Result result) {
    this.result = result;
    explicitEvent(ExplicitEvent.HANDLE_RESULT);
  }

  /** Called once per workflow task for the last WorkflowTaskStarted event in the history. */
  public void nonReplayWorkflowTaskStarted() {
    explicitEvent(ExplicitEvent.NON_REPLAY_WORKFLOW_TASK_STARTED);
  }

  private void createMarker() {
    RecordMarkerCommandAttributes.Builder markerAttributes =
        RecordMarkerCommandAttributes.newBuilder();
    Map<String, Payloads> details = new HashMap<>();
    if (!replaying.apply()) {
      markerAttributes.setMarkerName(LOCAL_ACTIVITY_MARKER_NAME);
      Payloads id = dataConverter.toPayloads(activityId).get();
      details.put(MARKER_ACTIVITY_ID_KEY, id);
      Payloads type = dataConverter.toPayloads(activityType.getName()).get();
      details.put(MARKER_ACTIVITY_TYPE_KEY, type);

      long elapsedNanoseconds = System.nanoTime() - systemNanoTimeWhenStarted;
      long currentTime =
          setCurrentTimeCallback.apply(
              workflowTimeMillisWhenStarted + TimeUnit.NANOSECONDS.toMillis(elapsedNanoseconds));
      Payloads t = dataConverter.toPayloads(currentTime).get();
      details.put(MARKER_TIME_KEY, t);

      if (localActivityParameters != null
          && !localActivityParameters.isDoNotIncludeArgumentsIntoMarker()) {
        details.put(
            MARKER_ACTIVITY_INPUT_KEY, localActivityParameters.getActivityTask().getInput());
      }
      if (result.getTaskCompleted() != null) {
        RespondActivityTaskCompletedRequest completed = result.getTaskCompleted();
        if (completed.hasResult()) {
          Payloads p = completed.getResult();
          laResult = Optional.of(p);
          details.put(MARKER_ACTIVITY_RESULT_KEY, p);
        } else {
          laResult = Optional.empty();
        }
      } else if (result.getTaskFailed() != null) {
        // TODO(maxim): Result should contain Failure, not an exception
        ActivityTaskHandler.Result.TaskFailedResult failed = result.getTaskFailed();
        // TODO(maxim): Return RetryState in the result
        RetryState retryState =
            hasRetryPolicy
                ? RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED
                : RetryState.RETRY_STATE_RETRY_POLICY_NOT_SET;
        failure =
            Failure.newBuilder()
                .setActivityFailureInfo(
                    ActivityFailureInfo.newBuilder()
                        .setRetryState(retryState)
                        .setActivityId(activityId)
                        .setActivityType(activityType))
                .setCause(FailureConverter.exceptionToFailure(failed.getFailure()))
                .build();
        markerAttributes.setFailure(failure);
      } else if (result.getTaskCanceled() != null) {
        RespondActivityTaskCanceledRequest failed = result.getTaskCanceled();
        markerAttributes.setFailure(
            Failure.newBuilder()
                .setCanceledFailureInfo(
                    CanceledFailureInfo.newBuilder().setDetails(failed.getDetails())));
      }
      markerAttributes.putAllDetails(details);
    }
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
            .setRecordMarkerCommandAttributes(markerAttributes.build())
            .build());
  }

  private void createFakeCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
            .setRecordMarkerCommandAttributes(RecordMarkerCommandAttributes.getDefaultInstance())
            .build());
  }

  private void notifyResultFromEvent() {
    MarkerRecordedEventAttributes attributes = currentEvent.getMarkerRecordedEventAttributes();
    if (!attributes.getMarkerName().equals(LOCAL_ACTIVITY_MARKER_NAME)) {
      throw new IllegalStateException(
          "Expected " + LOCAL_ACTIVITY_MARKER_NAME + ", received: " + attributes);
    }
    Map<String, Payloads> map = attributes.getDetailsMap();
    Optional<Payloads> timePayloads = Optional.ofNullable(map.get(MARKER_TIME_KEY));
    long time = dataConverter.fromPayloads(0, timePayloads, Long.class, Long.class);
    setCurrentTimeCallback.apply(time);
    if (attributes.hasFailure()) {
      callback.apply(null, attributes.getFailure());
      return;
    }
    Payloads result = map.get(MARKER_ACTIVITY_RESULT_KEY);
    if (result == null) {
      // Support old histories that used "data" as a key for "result".
      result = map.get(MARKER_DATA_KEY);
    }
    Optional<Payloads> fromMaker = Optional.ofNullable(result);
    callback.apply(fromMaker, null);
  }

  private void notifyResultFromResponse() {
    callback.apply(laResult, failure);
  }
}
