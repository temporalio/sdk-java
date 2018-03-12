/*
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

package com.uber.cadence.internal.replay;

import com.uber.cadence.ActivityTaskCanceledEventAttributes;
import com.uber.cadence.ActivityTaskCompletedEventAttributes;
import com.uber.cadence.ActivityTaskFailedEventAttributes;
import com.uber.cadence.ActivityTaskStartedEventAttributes;
import com.uber.cadence.ActivityTaskTimedOutEventAttributes;
import com.uber.cadence.ActivityType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.ScheduleActivityTaskDecisionAttributes;
import com.uber.cadence.TaskList;
import com.uber.cadence.TimeoutType;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class ActivityDecisionContext {

  private final class ActivityCancellationHandler implements Consumer<Exception> {

    private final String activityId;

    private final BiConsumer<byte[], Exception> callback;

    private ActivityCancellationHandler(
        String activityId, BiConsumer<byte[], Exception> callaback) {
      this.activityId = activityId;
      this.callback = callaback;
    }

    @Override
    public void accept(Exception cause) {
      decisions.requestCancelActivityTask(
          activityId,
          () -> {
            OpenRequestInfo<byte[], ActivityType> scheduled =
                scheduledActivities.remove(activityId);
            if (scheduled == null) {
              throw new IllegalArgumentException(
                  "Activity \"" + activityId + "\" wasn't scheduled");
            }
            callback.accept(null, new CancellationException("Cancelled by request"));
          });
    }
  }

  private final DecisionsHelper decisions;

  private final Map<String, OpenRequestInfo<byte[], ActivityType>> scheduledActivities =
      new HashMap<>();

  ActivityDecisionContext(DecisionsHelper decisions) {
    this.decisions = decisions;
  }

  Consumer<Exception> scheduleActivityTask(
      ExecuteActivityParameters parameters, BiConsumer<byte[], Exception> callback) {
    final OpenRequestInfo<byte[], ActivityType> context =
        new OpenRequestInfo<>(parameters.getActivityType());
    final ScheduleActivityTaskDecisionAttributes attributes =
        new ScheduleActivityTaskDecisionAttributes();
    attributes.setActivityType(parameters.getActivityType());
    attributes.setInput(parameters.getInput());
    if (parameters.getHeartbeatTimeoutSeconds() > 0) {
      attributes.setHeartbeatTimeoutSeconds((int) parameters.getHeartbeatTimeoutSeconds());
    }
    attributes.setScheduleToCloseTimeoutSeconds(
        (int) parameters.getScheduleToCloseTimeoutSeconds());
    attributes.setScheduleToStartTimeoutSeconds(
        (int) parameters.getScheduleToStartTimeoutSeconds());
    attributes.setStartToCloseTimeoutSeconds((int) parameters.getStartToCloseTimeoutSeconds());
    //        attributes.setTaskPriority(InternalUtils.taskPriorityToString(parameters.getTaskPriority()));
    String activityId = parameters.getActivityId();
    if (activityId == null) {
      activityId = String.valueOf(decisions.getNextId());
    }
    attributes.setActivityId(activityId);

    String taskList = parameters.getTaskList();
    if (taskList != null && !taskList.isEmpty()) {
      TaskList tl = new TaskList();
      tl.setName(taskList);
      attributes.setTaskList(tl);
    }
    decisions.scheduleActivityTask(attributes);
    context.setCompletionHandle(callback);
    scheduledActivities.put(attributes.getActivityId(), context);
    return new ActivityDecisionContext.ActivityCancellationHandler(
        attributes.getActivityId(), callback);
  }

  void handleActivityTaskStarted(ActivityTaskStartedEventAttributes attributes) {}

  void handleActivityTaskCanceled(HistoryEvent event) {
    ActivityTaskCanceledEventAttributes attributes = event.getActivityTaskCanceledEventAttributes();
    String activityId = decisions.getActivityId(attributes);
    if (decisions.handleActivityTaskCanceled(event)) {
      CancellationException e = new CancellationException();
      OpenRequestInfo<byte[], ActivityType> scheduled = scheduledActivities.remove(activityId);
      if (scheduled != null) {
        BiConsumer<byte[], Exception> completionHandle = scheduled.getCompletionCallback();
        // It is OK to fail with subclass of CancellationException when cancellation requested.
        // It allows passing information about cancellation (details in this case) to the surrounding doCatch block
        completionHandle.accept(null, e);
      }
    }
  }

  void handleActivityTaskCompleted(HistoryEvent event) {
    ActivityTaskCompletedEventAttributes attributes =
        event.getActivityTaskCompletedEventAttributes();
    String activityId = decisions.getActivityId(attributes);
    if (decisions.handleActivityTaskClosed(activityId)) {
      OpenRequestInfo<byte[], ActivityType> scheduled = scheduledActivities.remove(activityId);
      if (scheduled != null) {
        byte[] result = attributes.getResult();
        BiConsumer<byte[], Exception> completionHandle = scheduled.getCompletionCallback();
        completionHandle.accept(result, null);
      }
    }
  }

  void handleActivityTaskFailed(HistoryEvent event) {
    ActivityTaskFailedEventAttributes attributes = event.getActivityTaskFailedEventAttributes();
    String activityId = decisions.getActivityId(attributes);
    if (decisions.handleActivityTaskClosed(activityId)) {
      OpenRequestInfo<byte[], ActivityType> scheduled = scheduledActivities.remove(activityId);
      if (scheduled != null) {
        String reason = attributes.getReason();
        byte[] details = attributes.getDetails();
        ActivityTaskFailedException failure =
            new ActivityTaskFailedException(
                event.getEventId(), scheduled.getUserContext(), activityId, reason, details);
        BiConsumer<byte[], Exception> completionHandle = scheduled.getCompletionCallback();
        completionHandle.accept(null, failure);
      }
    }
  }

  void handleActivityTaskTimedOut(HistoryEvent event) {
    ActivityTaskTimedOutEventAttributes attributes = event.getActivityTaskTimedOutEventAttributes();
    String activityId = decisions.getActivityId(attributes);
    if (decisions.handleActivityTaskClosed(activityId)) {
      OpenRequestInfo<byte[], ActivityType> scheduled = scheduledActivities.remove(activityId);
      if (scheduled != null) {
        TimeoutType timeoutType = attributes.getTimeoutType();
        byte[] details = attributes.getDetails();
        ActivityTaskTimeoutException failure =
            new ActivityTaskTimeoutException(
                event.getEventId(), scheduled.getUserContext(), activityId, timeoutType, details);
        BiConsumer<byte[], Exception> completionHandle = scheduled.getCompletionCallback();
        completionHandle.accept(null, failure);
      }
    }
  }
}
