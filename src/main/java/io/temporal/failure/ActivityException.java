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

package io.temporal.failure;

import io.temporal.proto.common.RetryStatus;

public final class ActivityException extends TemporalFailure {

  private final long scheduledEventId;
  private final long startedEventId;
  private final String activityType;
  private final String activityId;
  private final String identity;
  private final RetryStatus retryStatus;

  public ActivityException(
      long scheduledEventId,
      long startedEventId,
      String activityType,
      String activityId,
      RetryStatus retryStatus,
      String identity,
      Throwable cause) {
    super(
        getMessage(
            scheduledEventId, startedEventId, activityType, activityId, retryStatus, identity),
        null,
        cause);
    this.scheduledEventId = scheduledEventId;
    this.startedEventId = startedEventId;
    this.activityType = activityType;
    this.activityId = activityId;
    this.identity = identity;
    this.retryStatus = retryStatus;
  }

  public long getScheduledEventId() {
    return scheduledEventId;
  }

  public long getStartedEventId() {
    return startedEventId;
  }

  public String getActivityType() {
    return activityType;
  }

  public String getActivityId() {
    return activityId;
  }

  public String getIdentity() {
    return identity;
  }

  public RetryStatus getRetryStatus() {
    return retryStatus;
  }

  public static String getMessage(
      long scheduledEventId,
      long startedEventId,
      String activityType,
      String activityId,
      RetryStatus retryStatus,
      String identity) {
    return "scheduledEventId="
        + scheduledEventId
        + ", startedEventId="
        + startedEventId
        + ", activityType='"
        + activityType
        + '\''
        + (activityId == null ? "" : ", activityId='" + activityId + '\'')
        + ", identity='"
        + identity
        + '\''
        + ", retryStatus="
        + retryStatus;
  }
}
