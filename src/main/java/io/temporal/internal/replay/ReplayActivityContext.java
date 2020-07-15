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

import static io.temporal.failure.FailureConverter.JAVA_SDK;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.ActivityTaskCanceledEventAttributes;
import io.temporal.api.history.v1.ActivityTaskCompletedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskFailedEventAttributes;
import io.temporal.api.history.v1.ActivityTaskTimedOutEventAttributes;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.failure.CanceledFailure;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class ReplayActivityContext {

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
            OpenRequestInfo<Optional<Payloads>, OpenActivityInfo> scheduled =
                scheduledActivities.remove(scheduledEventId);
            if (scheduled == null) {
              throw new IllegalArgumentException(
                  String.format(
                      "Activity with activityId=%s and scheduledEventId=%d wasn't found",
                      activityId, scheduledEventId));
            }
            callback.accept(null, new CanceledFailure("Cancelled by request"));
          };
      if (cancellationType != ActivityCancellationType.WAIT_CANCELLATION_COMPLETED) {
        immediateCancellationCallback.run();
        immediateCancellationCallback = () -> {};
      }
      if (cancellationType != ActivityCancellationType.ABANDON) {
        commandHelper.requestCancelActivityTask(scheduledEventId, immediateCancellationCallback);
      }
    }
  }

  private final CommandHelper commandHelper;

  private static class OpenActivityInfo {
    private final ActivityType activityType;
    private final String activityId;
    private final long scheduledEventId;
    private long startedEventId;

    private OpenActivityInfo(ActivityType activityType, String activityId, long scheduledEventId) {
      this.activityType = activityType;
      this.activityId = activityId;
      this.scheduledEventId = scheduledEventId;
    }

    public ActivityType getActivityType() {
      return activityType;
    }

    public String getActivityId() {
      return activityId;
    }

    public long getScheduledEventId() {
      return scheduledEventId;
    }

    public long getStartedEventId() {
      return startedEventId;
    }

    public void setStartedEventId(long startedEventId) {
      this.startedEventId = startedEventId;
    }
  }

  // key is scheduledEventId
  private final Map<Long, OpenRequestInfo<Optional<Payloads>, OpenActivityInfo>>
      scheduledActivities = new HashMap<>();

  ReplayActivityContext(CommandHelper commandHelper) {
    this.commandHelper = commandHelper;
  }

  Consumer<Exception> scheduleActivityTask(
      ExecuteActivityParameters parameters, BiConsumer<Optional<Payloads>, Exception> callback) {
    final ScheduleActivityTaskCommandAttributes.Builder attributes = parameters.getAttributes();

    if (attributes.getActivityId().isEmpty()) {
      attributes.setActivityId(commandHelper.getAndIncrementNextId());
    }

    long scheduledEventId = commandHelper.scheduleActivityTask(attributes.build());
    final OpenRequestInfo<Optional<Payloads>, OpenActivityInfo> context =
        new OpenRequestInfo<>(
            new OpenActivityInfo(
                attributes.getActivityType(), attributes.getActivityId(), scheduledEventId));
    context.setCompletionHandle(callback);
    scheduledActivities.put(scheduledEventId, context);
    return new ReplayActivityContext.ActivityCancellationHandler(
        scheduledEventId, attributes.getActivityId(), callback, parameters.getCancellationType());
  }

  void handleActivityTaskCanceled(HistoryEvent event) {
    ActivityTaskCanceledEventAttributes attributes = event.getActivityTaskCanceledEventAttributes();
    if (commandHelper.handleActivityTaskCanceled(event)) {
      Failure failure =
          Failure.newBuilder()
              .setSource(JAVA_SDK)
              .setCanceledFailureInfo(
                  CanceledFailureInfo.newBuilder().setDetails(attributes.getDetails()))
              .build();
      FailureWrapperException e = new FailureWrapperException(failure);
      OpenRequestInfo<Optional<Payloads>, OpenActivityInfo> scheduled =
          scheduledActivities.remove(attributes.getScheduledEventId());
      if (scheduled != null) {
        BiConsumer<Optional<Payloads>, Exception> completionHandle =
            scheduled.getCompletionCallback();
        // It is OK to fail with subclass of CanceledException when cancellation requested.
        // It allows passing information about cancellation (details in this case) to the
        // surrounding doCatch block
        completionHandle.accept(Optional.empty(), e);
      }
    }
  }

  void handleActivityTaskCompleted(HistoryEvent event) {
    ActivityTaskCompletedEventAttributes attributes =
        event.getActivityTaskCompletedEventAttributes();
    if (commandHelper.handleActivityTaskClosed(attributes.getScheduledEventId())) {
      OpenRequestInfo<Optional<Payloads>, OpenActivityInfo> scheduled =
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
    if (commandHelper.handleActivityTaskClosed(attributes.getScheduledEventId())) {
      OpenRequestInfo<Optional<Payloads>, OpenActivityInfo> scheduled =
          scheduledActivities.remove(attributes.getScheduledEventId());
      if (scheduled != null) {
        OpenActivityInfo context = scheduled.getUserContext();
        ActivityTaskFailedException failure =
            new ActivityTaskFailedException(
                event.getEventId(),
                attributes.getScheduledEventId(),
                attributes.getStartedEventId(),
                context.getActivityType(),
                context.getActivityId(),
                attributes.getFailure());
        BiConsumer<Optional<Payloads>, Exception> completionHandle =
            scheduled.getCompletionCallback();
        completionHandle.accept(Optional.empty(), failure);
      }
    }
  }

  void handleActivityTaskTimedOut(HistoryEvent event) {
    ActivityTaskTimedOutEventAttributes attributes = event.getActivityTaskTimedOutEventAttributes();
    if (commandHelper.handleActivityTaskClosed(attributes.getScheduledEventId())) {
      OpenRequestInfo<Optional<Payloads>, OpenActivityInfo> scheduled =
          scheduledActivities.remove(attributes.getScheduledEventId());
      if (scheduled != null) {
        Failure failure = attributes.getFailure();
        OpenActivityInfo context = scheduled.getUserContext();
        ActivityTaskTimeoutException timeoutException =
            new ActivityTaskTimeoutException(
                event.getEventId(),
                context.getScheduledEventId(),
                context.getStartedEventId(),
                context.getActivityType(),
                context.getActivityId(),
                attributes.getRetryState(),
                failure);
        BiConsumer<Optional<Payloads>, Exception> completionHandle =
            scheduled.getCompletionCallback();
        completionHandle.accept(Optional.empty(), timeoutException);
      }
    }
  }
}
