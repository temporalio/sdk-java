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

import static io.temporal.internal.common.DataConverterUtils.toHeaderGrpc;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.internal.common.RetryParameters;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.common.Header;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.TimeoutType;
import io.temporal.proto.decision.ScheduleActivityTaskDecisionAttributes;
import io.temporal.proto.event.ActivityTaskCanceledEventAttributes;
import io.temporal.proto.event.ActivityTaskCompletedEventAttributes;
import io.temporal.proto.event.ActivityTaskFailedEventAttributes;
import io.temporal.proto.event.ActivityTaskTimedOutEventAttributes;
import io.temporal.proto.event.HistoryEvent;
import io.temporal.proto.tasklist.TaskList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class ActivityDecisionContext {

  private final class ActivityCancellationHandler implements Consumer<Exception> {

    private final long scheduledEventId;

    private final String activityId;

    private final BiConsumer<Optional<Payloads>, Exception> callback;

    private final ActivityCancellationType cancellationType;

    private ActivityCancellationHandler(
        long scheduledEventId,
        String activityId,
        BiConsumer<Optional<Payloads>, Exception> callaback,
        ActivityCancellationType cancellationType) {
      this.scheduledEventId = scheduledEventId;
      this.activityId = activityId;
      this.callback = callaback;
      this.cancellationType = cancellationType;
    }

    @Override
    public void accept(Exception cause) {
      if (!scheduledActivities.containsKey(scheduledEventId)) {
        // Cancellation handlers are not deregistered. So they fire after an activity completion.
        return;
      }
      Runnable immediateCancellationCallback =
          () -> {
            OpenRequestInfo<Optional<Payloads>, ActivityType> scheduled =
                scheduledActivities.remove(scheduledEventId);
            if (scheduled == null) {
              throw new IllegalArgumentException(
                  String.format(
                      "Activity with activityId=%s and scheduledEventId=%d wasn't found",
                      activityId, scheduledEventId));
            }
            callback.accept(null, new CancellationException("Cancelled by request"));
          };
      if (cancellationType != ActivityCancellationType.WAIT_CANCELLATION_COMPLETED) {
        immediateCancellationCallback.run();
        immediateCancellationCallback = () -> {};
      }
      if (cancellationType != ActivityCancellationType.ABANDON) {
        decisions.requestCancelActivityTask(scheduledEventId, immediateCancellationCallback);
      }
    }
  }

  private final DecisionsHelper decisions;

  // key is scheduledEventId
  private final Map<Long, OpenRequestInfo<Optional<Payloads>, ActivityType>> scheduledActivities =
      new HashMap<>();

  ActivityDecisionContext(DecisionsHelper decisions) {
    this.decisions = decisions;
  }

  Consumer<Exception> scheduleActivityTask(
      ExecuteActivityParameters parameters, BiConsumer<Optional<Payloads>, Exception> callback) {
    final OpenRequestInfo<Optional<Payloads>, ActivityType> context =
        new OpenRequestInfo<>(parameters.getActivityType());
    final ScheduleActivityTaskDecisionAttributes.Builder attributes =
        ScheduleActivityTaskDecisionAttributes.newBuilder()
            .setActivityType(parameters.getActivityType());
    if (parameters.getInput() != null) {
      attributes.setInput(parameters.getInput());
    }
    if (parameters.getHeartbeatTimeoutSeconds() > 0) {
      attributes.setHeartbeatTimeoutSeconds((int) parameters.getHeartbeatTimeoutSeconds());
    }
    attributes.setScheduleToCloseTimeoutSeconds(
        (int) parameters.getScheduleToCloseTimeoutSeconds());
    attributes.setScheduleToStartTimeoutSeconds(
        (int) parameters.getScheduleToStartTimeoutSeconds());
    attributes.setStartToCloseTimeoutSeconds((int) parameters.getStartToCloseTimeoutSeconds());

    // attributes.setTaskPriority(InternalUtils.taskPriorityToString(parameters.getTaskPriority()));
    String activityId = parameters.getActivityId();
    if (activityId == null) {
      activityId = String.valueOf(decisions.getAndIncrementNextId());
    }
    attributes.setActivityId(activityId);

    String taskList = parameters.getTaskList();
    if (taskList != null && !taskList.isEmpty()) {
      attributes.setTaskList(TaskList.newBuilder().setName(taskList).build());
    }
    RetryParameters retryParameters = parameters.getRetryParameters();
    if (retryParameters != null) {
      attributes.setRetryPolicy(retryParameters.toRetryPolicy());
    }

    Header header = toHeaderGrpc(parameters.getContext());
    if (header != null) {
      attributes.setHeader(header);
    }

    long scheduledEventId = decisions.scheduleActivityTask(attributes.build());
    context.setCompletionHandle(callback);
    scheduledActivities.put(scheduledEventId, context);
    return new ActivityDecisionContext.ActivityCancellationHandler(
        scheduledEventId, attributes.getActivityId(), callback, parameters.getCancellationType());
  }

  void handleActivityTaskCanceled(HistoryEvent event) {
    ActivityTaskCanceledEventAttributes attributes = event.getActivityTaskCanceledEventAttributes();
    if (decisions.handleActivityTaskCanceled(event)) {
      CancellationException e = new CancellationException();
      OpenRequestInfo<Optional<Payloads>, ActivityType> scheduled =
          scheduledActivities.remove(attributes.getScheduledEventId());
      if (scheduled != null) {
        BiConsumer<Optional<Payloads>, Exception> completionHandle =
            scheduled.getCompletionCallback();
        // It is OK to fail with subclass of CancellationException when cancellation requested.
        // It allows passing information about cancellation (details in this case) to the
        // surrounding doCatch block
        completionHandle.accept(Optional.empty(), e);
      }
    }
  }

  void handleActivityTaskCompleted(HistoryEvent event) {
    ActivityTaskCompletedEventAttributes attributes =
        event.getActivityTaskCompletedEventAttributes();
    if (decisions.handleActivityTaskClosed(attributes.getScheduledEventId())) {
      OpenRequestInfo<Optional<Payloads>, ActivityType> scheduled =
          scheduledActivities.remove(attributes.getScheduledEventId());
      if (scheduled != null) {
        Optional<Payloads> result =
            attributes.hasResult() ? Optional.of(attributes.getResult()) : Optional.empty();
        BiConsumer<Optional<Payloads>, Exception> completionHandle =
            scheduled.getCompletionCallback();
        completionHandle.accept(result, null);
      } else {
        throw new NonDeterminisicWorkflowError(
            "Trying to complete activity event "
                + attributes.getScheduledEventId()
                + " that is not in scheduledActivities");
      }
    }
  }

  void handleActivityTaskFailed(HistoryEvent event) {
    ActivityTaskFailedEventAttributes attributes = event.getActivityTaskFailedEventAttributes();
    if (decisions.handleActivityTaskClosed(attributes.getScheduledEventId())) {
      OpenRequestInfo<Optional<Payloads>, ActivityType> scheduled =
          scheduledActivities.remove(attributes.getScheduledEventId());
      if (scheduled != null) {
        String reason = attributes.getReason();
        Optional<Payloads> details =
            attributes.hasDetails() ? Optional.of(attributes.getDetails()) : Optional.empty();
        ActivityTaskFailedException failure =
            new ActivityTaskFailedException(
                event.getEventId(), scheduled.getUserContext(), null, reason, details);
        BiConsumer<Optional<Payloads>, Exception> completionHandle =
            scheduled.getCompletionCallback();
        completionHandle.accept(Optional.empty(), failure);
      }
    }
  }

  void handleActivityTaskTimedOut(HistoryEvent event) {
    ActivityTaskTimedOutEventAttributes attributes = event.getActivityTaskTimedOutEventAttributes();
    if (decisions.handleActivityTaskClosed(attributes.getScheduledEventId())) {
      OpenRequestInfo<Optional<Payloads>, ActivityType> scheduled =
          scheduledActivities.remove(attributes.getScheduledEventId());
      if (scheduled != null) {
        TimeoutType timeoutType = attributes.getTimeoutType();
        Optional<Payloads> details =
            attributes.hasDetails() ? Optional.of(attributes.getDetails()) : Optional.empty();
        ActivityTaskTimeoutException failure =
            new ActivityTaskTimeoutException(
                event.getEventId(), scheduled.getUserContext(), null, timeoutType, details);
        BiConsumer<Optional<Payloads>, Exception> completionHandle =
            scheduled.getCompletionCallback();
        completionHandle.accept(Optional.empty(), failure);
      }
    }
  }
}
