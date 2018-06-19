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

package com.uber.cadence.workflow;

import com.uber.cadence.ActivityType;
import com.uber.cadence.TimeoutType;
import com.uber.cadence.converter.DataConverter;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * ActivityTimeoutException indicates that an activity has timed out. If the timeout type is a
 * {@link TimeoutType#HEARTBEAT} then the {@link #getDetails(Class)} returns a value passed to the
 * latest successful {@link com.uber.cadence.activity.Activity#heartbeat(Object)} call.
 */
@SuppressWarnings("serial")
public final class ActivityTimeoutException extends ActivityException {

  private final TimeoutType timeoutType;

  private final byte[] details;
  private final DataConverter dataConverter;

  public ActivityTimeoutException(
      long eventId,
      ActivityType activityType,
      String activityId,
      TimeoutType timeoutType,
      byte[] details,
      DataConverter dataConverter) {
    super("TimeoutType=" + String.valueOf(timeoutType), eventId, activityType, activityId);
    this.timeoutType = Objects.requireNonNull(timeoutType);
    this.details = details;
    this.dataConverter = Objects.requireNonNull(dataConverter);
  }

  public TimeoutType getTimeoutType() {
    return timeoutType;
  }

  /** @return The value from the last activity heartbeat details field. */
  public <V> V getDetails(Class<V> detailsClass) {
    return dataConverter.fromData(details, detailsClass, detailsClass);
  }

  /** @return The value from the last activity heartbeat details field. */
  public <V> V getDetails(Class<V> detailsClass, Type detailsType) {
    return dataConverter.fromData(details, detailsClass, detailsType);
  }
}
