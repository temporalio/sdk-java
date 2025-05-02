package io.temporal.internal.worker;

import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponseOrBuilder;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.workflow.Functions;
import javax.annotation.Nonnull;

public final class ActivityTask {
  private final @Nonnull PollActivityTaskQueueResponseOrBuilder response;
  private final @Nonnull SlotPermit permit;
  private final @Nonnull Functions.Proc completionCallback;

  public ActivityTask(
      @Nonnull PollActivityTaskQueueResponseOrBuilder response,
      @Nonnull SlotPermit permit,
      @Nonnull Functions.Proc completionCallback) {
    this.response = response;
    this.permit = permit;
    this.completionCallback = completionCallback;
  }

  @Nonnull
  public PollActivityTaskQueueResponseOrBuilder getResponse() {
    return response;
  }

  /**
   * Completion handle function that must be called by the handler whenever activity processing is
   * completed.
   */
  @Nonnull
  public Functions.Proc getCompletionCallback() {
    return completionCallback;
  }

  @Nonnull
  public SlotPermit getPermit() {
    return permit;
  }
}
