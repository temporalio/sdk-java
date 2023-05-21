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

package io.temporal.client;

import io.temporal.activity.ActivityInfo;
import io.temporal.api.enums.v1.TimeoutType;

/**
 * Indicates that the activity task has timed out while executing.
 *
 * <p>Catching this exception directly is discouraged and catching the parent class {@link
 * ActivityCompletionException} is recommended instead.<br>
 */
public final class ActivityTaskTimedOutException extends ActivityCompletionException {
  private final TimeoutType timeoutType;
  private final long timeoutDeadline;

  public ActivityTaskTimedOutException(
      ActivityInfo info, TimeoutType timeoutType, long timeoutDeadline) {
    super(info);
    this.timeoutType = timeoutType;
    this.timeoutDeadline = timeoutDeadline;
  }

  /**
   * Gets the type of timeout that was exceeded during activity execution
   *
   * @return only TIMEOUT_TYPE_START_TO_CLOSE or TIMEOUT_TYPE_SCHEDULE_TO_CLOSE
   */
  public TimeoutType getTimeoutType() {
    return timeoutType;
  }

  /**
   * Gets the timeout deadline that was exceeded during activity execution
   *
   * @return Timestamp in milliseconds (UNIX Epoch time)
   */
  public long getTimeoutDeadline() {
    return timeoutDeadline;
  }
}
