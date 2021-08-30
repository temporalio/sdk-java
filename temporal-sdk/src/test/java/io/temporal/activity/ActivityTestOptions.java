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
}
