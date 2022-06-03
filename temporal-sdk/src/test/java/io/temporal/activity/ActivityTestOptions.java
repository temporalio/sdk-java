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

package io.temporal.activity;

import io.temporal.common.RetryOptions;
import java.time.Duration;

public class ActivityTestOptions {
  public static ActivityOptions newActivityOptions1() {
    return ActivityOptions.newBuilder()
        .setScheduleToStartTimeout(Duration.ofMillis(500))
        .setScheduleToCloseTimeout(Duration.ofDays(1))
        .setStartToCloseTimeout(Duration.ofSeconds(2))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
        .setCancellationType(ActivityCancellationType.TRY_CANCEL)
        .setContextPropagators(null)
        .build();
  }

  public static ActivityOptions newActivityOptions2() {
    return ActivityOptions.newBuilder()
        .setHeartbeatTimeout(Duration.ofSeconds(1))
        .setScheduleToStartTimeout(Duration.ofSeconds(3))
        .setScheduleToCloseTimeout(Duration.ofDays(3))
        .setStartToCloseTimeout(Duration.ofSeconds(3))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
        .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
        .setContextPropagators(null)
        .build();
  }

  public static LocalActivityOptions newLocalActivityOptions1() {
    return LocalActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofDays(1))
        .setStartToCloseTimeout(Duration.ofSeconds(2))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
        .setLocalRetryThreshold(Duration.ofSeconds(1))
        .setDoNotIncludeArgumentsIntoMarker(true)
        .build();
  }

  public static LocalActivityOptions newLocalActivityOptions2() {
    return LocalActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofDays(3))
        .setStartToCloseTimeout(Duration.ofSeconds(3))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
        .setLocalRetryThreshold(Duration.ofSeconds(3))
        .build();
  }
}
