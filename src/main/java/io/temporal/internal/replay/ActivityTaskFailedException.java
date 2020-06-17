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

import io.temporal.proto.common.ActivityType;
import io.temporal.proto.failure.Failure;

/**
 * Internal. Do not catch or throw in application level code. Exception used to communicate failure
 * of remote activity. TODO: Make package level visibility.
 */
@SuppressWarnings("serial")
public class ActivityTaskFailedException extends FailureWrapperException {

  private final long scheduledEventId;
  private final long startedEventId;
  private final long eventId;
  private final ActivityType activityType;
  private final String activityId;

  ActivityTaskFailedException(
      long eventId,
      long scheduledEventId,
      long startedEventId,
      ActivityType activityType,
      String activityId,
      Failure failure) {
    super(failure);
    this.scheduledEventId = scheduledEventId;
    this.startedEventId = startedEventId;
    this.eventId = eventId;
    this.activityType = activityType;
    this.activityId = activityId;
  }

  public long getScheduledEventId() {
    return scheduledEventId;
  }

  public long getStartedEventId() {
    return startedEventId;
  }

  public long getEventId() {
    return eventId;
  }

  public ActivityType getActivityType() {
    return activityType;
  }

  public String getActivityId() {
    return activityId;
  }
}
