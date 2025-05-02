package io.temporal.internal.worker;

import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import java.util.concurrent.ScheduledFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class LocalActivityAttemptTask {
  private final @Nonnull LocalActivityExecutionContext executionContext;
  private final @Nonnull PollActivityTaskQueueResponse.Builder attemptTask;
  private final @Nullable ScheduledFuture<?> scheduleToStartFuture;

  public LocalActivityAttemptTask(
      @Nonnull LocalActivityExecutionContext executionContext,
      @Nonnull PollActivityTaskQueueResponse.Builder attemptTask,
      @Nullable ScheduledFuture<?> scheduleToStartFuture) {
    this.executionContext = executionContext;
    this.attemptTask = attemptTask;
    this.scheduleToStartFuture = scheduleToStartFuture;
  }

  @Nonnull
  public LocalActivityExecutionContext getExecutionContext() {
    return executionContext;
  }

  public String getActivityId() {
    return executionContext.getActivityId();
  }

  @Nonnull
  public PollActivityTaskQueueResponse.Builder getAttemptTask() {
    return attemptTask;
  }

  @Nullable
  public ScheduledFuture<?> getScheduleToStartFuture() {
    return scheduleToStartFuture;
  }
}
