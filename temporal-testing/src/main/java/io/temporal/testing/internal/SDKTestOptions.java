package io.temporal.testing.internal;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;

public class SDKTestOptions {
  // When set to true increases test, activity and workflow timeouts to large values to support
  // stepping through code in a debugger without timing out.
  private static final boolean DEBUGGER_TIMEOUTS = false;

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

  public static ActivityOptions newActivityOptions() {
    if (DEBUGGER_TIMEOUTS) {
      return ActivityOptions.newBuilder()
          .setScheduleToCloseTimeout(Duration.ofSeconds(1000))
          .setHeartbeatTimeout(Duration.ofSeconds(1000))
          .setScheduleToStartTimeout(Duration.ofSeconds(1000))
          .setStartToCloseTimeout(Duration.ofSeconds(10000))
          .build();
    } else {
      return ActivityOptions.newBuilder()
          .setScheduleToCloseTimeout(Duration.ofSeconds(5))
          .setHeartbeatTimeout(Duration.ofSeconds(5))
          .setScheduleToStartTimeout(Duration.ofSeconds(5))
          .setStartToCloseTimeout(Duration.ofSeconds(5))
          .build();
    }
  }

  public static ActivityOptions newActivityOptionsForTaskQueue(String taskQueue) {
    return ActivityOptions.newBuilder(newActivityOptions()).setTaskQueue(taskQueue).build();
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
