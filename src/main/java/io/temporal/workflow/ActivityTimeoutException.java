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

package io.temporal.workflow;

import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.TimeoutType;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;

/**
 * ActivityTimeoutException indicates that an activity has timed out. If the timeout type is a
 * {@link TimeoutType#Heartbeat} then the {@link #getDetails(Class)} returns a value passed to the
 * latest successful {@link io.temporal.activity.Activity#heartbeat(Object)} call.
 */
@SuppressWarnings("serial")
public final class ActivityTimeoutException extends ActivityException {

  private final TimeoutType timeoutType;

  private final byte[] details;
  private DataConverter dataConverter;

  public ActivityTimeoutException(
      long eventId,
      ActivityType activityType,
      String activityId,
      TimeoutType timeoutType,
      Optional<Payloads> details,
      DataConverter dataConverter) {
    super("TimeoutType=" + timeoutType, eventId, activityType, activityId);
    this.timeoutType = Objects.requireNonNull(timeoutType);
    // Serialize to byte array as the exception itself has to be serialized
    this.details = details.isPresent() ? details.get().toByteArray() : null;
    this.dataConverter = Objects.requireNonNull(dataConverter);
  }

  public TimeoutType getTimeoutType() {
    return timeoutType;
  }

  /** @return The value from the last activity heartbeat details field. */
  public <V> V getDetails(Class<V> detailsClass) {
    return getDetails(detailsClass, detailsClass);
  }

  /** @return The value from the last activity heartbeat details field. */
  public <V> V getDetails(Class<V> detailsClass, Type detailsType) {
    Optional<Payloads> payloads;
    try {
      payloads = details == null ? Optional.empty() : Optional.of(Payloads.parseFrom(details));
    } catch (InvalidProtocolBufferException e) {
      throw new DataConverterException(e);
    }
    return dataConverter.fromData(payloads, detailsClass, detailsType);
  }
}
