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

import com.google.common.base.Preconditions;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.ActivityFailureInfo;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCompletedRequest;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.history.LocalActivityMarkerMetadata;
import io.temporal.internal.history.LocalActivityMarkerUtils;
import io.temporal.internal.worker.LocalActivityResult;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

final class LocalActivityStateMachine
    extends EntityStateMachineInitialCommand<
        LocalActivityStateMachine.State,
        LocalActivityStateMachine.ExplicitEvent,
        LocalActivityStateMachine> {
  static final String LOCAL_ACTIVITY_FAILED_MESSAGE =
      "Local " + ActivityStateMachine.ACTIVITY_FAILED_MESSAGE;

  static final String LOCAL_ACTIVITY_TIMED_OUT_MESSAGE =
      "Local " + ActivityStateMachine.ACTIVITY_TIMED_OUT_MESSAGE;

  static final String LOCAL_ACTIVITY_CANCELED_MESSAGE =
      "Local " + ActivityStateMachine.ACTIVITY_CANCELED_MESSAGE;

  private final Functions.Proc1<ExecuteLocalActivityParameters> localActivityRequestSink;
  private final LocalActivityCallback callback;

  private ExecuteLocalActivityParameters localActivityParameters;
  private final Functions.Func<Boolean> replaying;
  /** Accepts proposed current time. Returns accepted current time. */
  private final Functions.Func1<Long, Long> setCurrentTimeCallback;

  private final String activityId;
  private final ActivityType activityType;
  // The value from the marker is taking over this value in case of replay
  private final long originalScheduledTimestamp;

  /** Workflow timestamp when the LA state machine is initialized */
  private final long workflowTimeMillisWhenStarted;
  /**
   * System.nanoTime result at the moment of LA state machine initialization. May be used to
   * calculate elapsed time
   */
  private final long systemNanoTimeWhenStarted;

  // These three fields are set when an actual execution was performed instead of a replay from the
  // LA marker
  private @Nullable LocalActivityResult executionResult;
  private @Nullable Optional<Payloads> executionSuccess;
  private @Nullable LocalActivityCallback.LocalActivityFailedException executionFailure;

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
    WAITING_MARKER_EVENT,
    REQUEST_PREPARED,
    REQUEST_SENT,
    MARKER_COMMAND_CREATED,
    RESULT_NOTIFIED,
    MARKER_COMMAND_RECORDED
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
      LocalActivityCallback callback,
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
      LocalActivityCallback callback,
      Functions.Proc1<ExecuteLocalActivityParameters> localActivityRequestSink,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink,
      long workflowTimeMillisWhenStarted,
      long systemNanoTimeWhenStarted) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.replaying = replaying;
    this.setCurrentTimeCallback = setCurrentTimeCallback;
    this.localActivityParameters = localActivityParameters;
    this.activityId = localActivityParameters.getActivityId();
    this.activityType = localActivityParameters.getActivityType();
    this.originalScheduledTimestamp = localActivityParameters.getOriginalScheduledTimestamp();
    this.localActivityRequestSink = localActivityRequestSink;
    this.callback = callback;
    this.workflowTimeMillisWhenStarted = workflowTimeMillisWhenStarted;
    this.systemNanoTimeWhenStarted = systemNanoTimeWhenStarted;
    explicitEvent(ExplicitEvent.CHECK_EXECUTION_STATE);
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

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

  public void handleCompletion(LocalActivityResult result) {
    this.executionResult = result;
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
      markerAttributes.setMarkerName(LocalActivityMarkerUtils.MARKER_NAME);
      Payloads id = DefaultDataConverter.STANDARD_INSTANCE.toPayloads(activityId).get();
      details.put(LocalActivityMarkerUtils.MARKER_ACTIVITY_ID_KEY, id);
      Payloads type =
          DefaultDataConverter.STANDARD_INSTANCE.toPayloads(activityType.getName()).get();
      details.put(LocalActivityMarkerUtils.MARKER_ACTIVITY_TYPE_KEY, type);

      long elapsedNanoseconds = System.nanoTime() - systemNanoTimeWhenStarted;
      long currentTime =
          setCurrentTimeCallback.apply(
              workflowTimeMillisWhenStarted + TimeUnit.NANOSECONDS.toMillis(elapsedNanoseconds));
      Payloads t = DefaultDataConverter.STANDARD_INSTANCE.toPayloads(currentTime).get();
      details.put(LocalActivityMarkerUtils.MARKER_TIME_KEY, t);

      if (localActivityParameters != null
          && !localActivityParameters.isDoNotIncludeArgumentsIntoMarker()) {
        details.put(
            LocalActivityMarkerUtils.MARKER_ACTIVITY_INPUT_KEY, localActivityParameters.getInput());
      }
      Preconditions.checkState(
          executionResult != null,
          "Local activity execution result should be populated before triggering createMarker()");
      final LocalActivityMarkerMetadata localActivityMarkerMetadata =
          new LocalActivityMarkerMetadata(
              executionResult.getLastAttempt(), originalScheduledTimestamp);
      if (executionResult.getExecutionCompleted() != null) {
        RespondActivityTaskCompletedRequest completed = executionResult.getExecutionCompleted();
        if (completed.hasResult()) {
          Payloads p = completed.getResult();
          executionSuccess = Optional.of(p);
          details.put(LocalActivityMarkerUtils.MARKER_ACTIVITY_RESULT_KEY, p);
        } else {
          executionSuccess = Optional.empty();
        }
      } else if (executionResult.getExecutionFailed() != null) {
        LocalActivityResult.ExecutionFailedResult failedResult =
            executionResult.getExecutionFailed();
        String message =
            failedResult.isTimeout()
                ? LOCAL_ACTIVITY_TIMED_OUT_MESSAGE
                : LOCAL_ACTIVITY_FAILED_MESSAGE;
        Failure failure =
            Failure.newBuilder()
                .setMessage(message)
                .setActivityFailureInfo(
                    ActivityFailureInfo.newBuilder()
                        .setRetryState(failedResult.getRetryState())
                        .setActivityId(activityId)
                        .setActivityType(activityType))
                .setCause(failedResult.getFailure())
                .build();
        markerAttributes.setFailure(failure);

        localActivityMarkerMetadata.setBackoff(failedResult.getBackoff());
        executionFailure =
            new LocalActivityCallback.LocalActivityFailedException(
                failure,
                originalScheduledTimestamp,
                localActivityMarkerMetadata.getAttempt(),
                failedResult.getBackoff());
      } else if (executionResult.getExecutionCanceled() != null) {
        RespondActivityTaskCanceledRequest failed = executionResult.getExecutionCanceled();
        Failure failure =
            Failure.newBuilder()
                .setMessage(LOCAL_ACTIVITY_CANCELED_MESSAGE)
                .setCanceledFailureInfo(
                    CanceledFailureInfo.newBuilder().setDetails(failed.getDetails()))
                .build();
        markerAttributes.setFailure(failure);
        executionFailure =
            new LocalActivityCallback.LocalActivityFailedException(
                failure,
                originalScheduledTimestamp,
                localActivityMarkerMetadata.getAttempt(),
                null);
      }

      details.put(
          LocalActivityMarkerUtils.MARKER_METADATA_KEY,
          DefaultDataConverter.STANDARD_INSTANCE.toPayloads(localActivityMarkerMetadata).get());
      markerAttributes.putAllDetails(details);
    }
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
            .setRecordMarkerCommandAttributes(markerAttributes.build())
            .build());
  }

  private void notifyResultFromEvent() {
    MarkerRecordedEventAttributes attributes = currentEvent.getMarkerRecordedEventAttributes();
    Preconditions.checkState(
        LocalActivityMarkerUtils.hasLocalActivityStructure(currentEvent),
        "Expected " + LocalActivityMarkerUtils.MARKER_NAME + ", received: %s",
        attributes);
    long time =
        Preconditions.checkNotNull(
            LocalActivityMarkerUtils.getTime(attributes),
            "'time' payload of a LocalActivity marker can't be empty");
    setCurrentTimeCallback.apply(time);
    if (attributes.hasFailure()) {
      // In older markers metadata is missing
      @Nullable
      LocalActivityMarkerMetadata metadata = LocalActivityMarkerUtils.getMetadata(attributes);
      long originalScheduledTimestamp =
          metadata != null ? metadata.getOriginalScheduledTimestamp() : -1;
      int lastAttempt = metadata != null ? metadata.getAttempt() : 0;
      Duration backoff = metadata != null ? metadata.getBackoff() : null;
      LocalActivityCallback.LocalActivityFailedException localActivityFailedException =
          new LocalActivityCallback.LocalActivityFailedException(
              attributes.getFailure(), originalScheduledTimestamp, lastAttempt, backoff);
      callback.apply(null, localActivityFailedException);
    } else {
      Optional<Payloads> result =
          Optional.ofNullable(LocalActivityMarkerUtils.getResult(attributes));
      callback.apply(result, null);
    }
  }

  private void notifyResultFromResponse() {
    callback.apply(executionSuccess, executionFailure);
  }
}
