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
