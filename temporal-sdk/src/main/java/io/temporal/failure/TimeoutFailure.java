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

import com.google.common.base.Strings;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.converter.Values;

public final class TimeoutFailure extends TemporalFailure {
  private final Values lastHeartbeatDetails;
  private final TimeoutType timeoutType;

  public TimeoutFailure(String message, Object lastHeartbeatDetails, TimeoutType timeoutType) {
    this(message, new EncodedValues(lastHeartbeatDetails), timeoutType, null);
  }

  TimeoutFailure(
      String message, Values lastHeartbeatDetails, TimeoutType timeoutType, Throwable cause) {
    super(getMessage(message, timeoutType), message, cause);
    this.lastHeartbeatDetails = lastHeartbeatDetails;
    this.timeoutType = timeoutType;
  }

  public Values getLastHeartbeatDetails() {
    return lastHeartbeatDetails;
  }

  public TimeoutType getTimeoutType() {
    return timeoutType;
  }

  @Override
  public void setDataConverter(DataConverter converter) {
    ((EncodedValues) lastHeartbeatDetails).setDataConverter(converter);
  }

  public static String getMessage(String message, TimeoutType timeoutType) {
    return (Strings.isNullOrEmpty(message) ? "" : "message='" + message + "', ")
        + "timeoutType="
        + timeoutType;
  }
}
