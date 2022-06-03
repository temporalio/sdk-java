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

package io.temporal.failure;

import io.temporal.api.enums.v1.RetryState;

/**
 * Contains information about an activity failure. Always contains the original reason for the
 * failure as its cause. For example if an activity timed out the cause is {@link TimeoutFailure}.
 *
 * <p>This exception is expected to be thrown only by the framework code.
 */
public final class ActivityFailure extends TemporalFailure {

  private final long scheduledEventId;
  private final long startedEventId;
  private final String activityType;
  private final String activityId;
  private final String identity;
  private final RetryState retryState;

  public ActivityFailure(
      long scheduledEventId,
      long startedEventId,
      String activityType,
      String activityId,
      RetryState retryState,
      String identity,
      Throwable cause) {
    super(
        getMessage(
            scheduledEventId, startedEventId, activityType, activityId, retryState, identity),
        null,
        cause);
    this.scheduledEventId = scheduledEventId;
    this.startedEventId = startedEventId;
    this.activityType = activityType;
    this.activityId = activityId;
    this.identity = identity;
    this.retryState = retryState;
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

  public RetryState getRetryState() {
    return retryState;
  }

  public static String getMessage(
      long scheduledEventId,
      long startedEventId,
      String activityType,
      String activityId,
      RetryState retryState,
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
        + ", retryState="
        + retryState;
  }
}
