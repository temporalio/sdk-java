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

import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.failure.v1.Failure;

/**
 * Internal. Do not catch or throw in application level code. Exception that indicates Activity time
 * out.
 */
@SuppressWarnings("serial")
public final class ActivityTaskTimeoutException extends RuntimeException {

  private final long scheduledEventId;
  private final long startedEventId;
  private final long eventId;

  private final RetryState retryState;

  private final Failure failure;

  private final ActivityType activityType;

  private final String activityId;

  ActivityTaskTimeoutException(
      long eventId,
      long scheduledEventId,
      long startedEventId,
      ActivityType activityType,
      String activityId,
      RetryState retryState,
      Failure failure) {
    this.scheduledEventId = scheduledEventId;
    this.startedEventId = startedEventId;
    this.eventId = eventId;
    this.activityType = activityType;
    this.activityId = activityId;
    this.retryState = retryState;
    this.failure = failure;
  }

  public Failure getFailure() {
    return failure;
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

  public RetryState getRetryState() {
    return retryState;
  }

  public ActivityType getActivityType() {
    return activityType;
  }

  public String getActivityId() {
    return activityId;
  }
}
