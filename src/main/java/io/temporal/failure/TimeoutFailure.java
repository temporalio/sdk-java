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

import com.google.common.base.Strings;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValue;
import io.temporal.common.converter.Value;
import io.temporal.api.enums.v1.TimeoutType;

public final class TimeoutFailure extends TemporalFailure {
  private final Value lastHeartbeatDetails;
  private final TimeoutType timeoutType;

  public TimeoutFailure(String message, Object lastHeartbeatDetails, TimeoutType timeoutType) {
    this(message, new EncodedValue(lastHeartbeatDetails), timeoutType, null);
  }

  TimeoutFailure(
      String message, Value lastHeartbeatDetails, TimeoutType timeoutType, Throwable cause) {
    super(getMessage(message, timeoutType), message, cause);
    this.lastHeartbeatDetails = lastHeartbeatDetails;
    this.timeoutType = timeoutType;
  }

  public Value getLastHeartbeatDetails() {
    return lastHeartbeatDetails;
  }

  public TimeoutType getTimeoutType() {
    return timeoutType;
  }

  @Override
  public void setDataConverter(DataConverter converter) {
    ((EncodedValue) lastHeartbeatDetails).setDataConverter(converter);
  }

  public static String getMessage(String message, TimeoutType timeoutType) {
    return (Strings.isNullOrEmpty(message) ? "" : "message='" + message + "', ")
        + "timeoutType="
        + timeoutType;
  }
}
