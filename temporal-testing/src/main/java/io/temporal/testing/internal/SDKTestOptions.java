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

package io.temporal.testing.internal;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.time.Duration;

public class SDKTestOptions {
  // When set to true increases test, activity and workflow timeouts to large values to support
  // stepping through code in a debugger without timing out.
  private static final boolean DEBUGGER_TIMEOUTS = false;

  public static WorkflowImplementationOptions
      newWorkflowImplementationOptionsWithDefaultStartToCloseTimeout() {
    return WorkflowImplementationOptions.newBuilder()
        .setDefaultActivityOptions(
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(1)).build())
        .build();
  }

  public static WorkflowOptions newWorkflowOptionsForTaskQueue200sTimeout(String taskQueue) {
    return WorkflowOptions.newBuilder()
        .setWorkflowRunTimeout(Duration.ofSeconds(200))
        .setWorkflowTaskTimeout(Duration.ofSeconds(60))
        .setTaskQueue(taskQueue)
        .build();
  }

  public static WorkflowOptions newWorkflowOptionsWithTimeouts(String taskQueue) {
    if (DEBUGGER_TIMEOUTS) {
      return WorkflowOptions.newBuilder()
          .setWorkflowRunTimeout(Duration.ofSeconds(1000))
          .setWorkflowTaskTimeout(Duration.ofSeconds(60))
          .setTaskQueue(taskQueue)
          .build();
    } else {
      return WorkflowOptions.newBuilder()
          .setWorkflowRunTimeout(Duration.ofSeconds(200))
          .setWorkflowTaskTimeout(Duration.ofSeconds(5))
          .setTaskQueue(taskQueue)
          .build();
    }
  }

  public static ActivityOptions newActivityOptions20sScheduleToClose() {
    return ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(20)).build();
  }

  public static LocalActivityOptions newLocalActivityOptions20sScheduleToClose() {
    return LocalActivityOptions.newBuilder()
        .setScheduleToCloseTimeout(Duration.ofSeconds(20))
        .build();
  }

  public static ActivityOptions newActivityOptionsForTaskQueue(String taskQueue) {
    if (DEBUGGER_TIMEOUTS) {
      return ActivityOptions.newBuilder()
          .setTaskQueue(taskQueue)
          .setScheduleToCloseTimeout(Duration.ofSeconds(1000))
          .setHeartbeatTimeout(Duration.ofSeconds(1000))
          .setScheduleToStartTimeout(Duration.ofSeconds(1000))
          .setStartToCloseTimeout(Duration.ofSeconds(10000))
          .build();
    } else {
      return ActivityOptions.newBuilder()
          .setTaskQueue(taskQueue)
          .setScheduleToCloseTimeout(Duration.ofSeconds(5))
          .setHeartbeatTimeout(Duration.ofSeconds(5))
          .setScheduleToStartTimeout(Duration.ofSeconds(5))
          .setStartToCloseTimeout(Duration.ofSeconds(5))
          .build();
    }
  }

  public static LocalActivityOptions newLocalActivityOptions() {
    if (DEBUGGER_TIMEOUTS) {
      return LocalActivityOptions.newBuilder()
          .setScheduleToCloseTimeout(Duration.ofSeconds(1000))
          .build();
    } else {
      return LocalActivityOptions.newBuilder()
          .setScheduleToCloseTimeout(Duration.ofSeconds(5))
          .build();
    }
  }
}
