package io.temporal.internal.worker;

import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponseOrBuilder;
import io.temporal.internal.activity.LocalActivityArguments;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.workflow.Functions;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class ActivityTask implements ScalingTask {
  private final @Nonnull PollActivityTaskQueueResponseOrBuilder response;
  private final @Nonnull SlotPermit permit;
  private final @Nonnull Functions.Proc completionCallback;
  private final @Nullable LocalActivityArguments localActivityArguments;

  public ActivityTask(
      @Nonnull PollActivityTaskQueueResponseOrBuilder response,
      @Nonnull SlotPermit permit,
      @Nonnull Functions.Proc completionCallback) {
    this(response, permit, completionCallback, null);
  }

  public ActivityTask(
      @Nonnull PollActivityTaskQueueResponseOrBuilder response,
      @Nonnull SlotPermit permit,
      @Nonnull Functions.Proc completionCallback,
      @Nullable LocalActivityArguments localActivityArguments) {
    this.response = response;
    this.permit = permit;
    this.completionCallback = completionCallback;
    this.localActivityArguments = localActivityArguments;
  }

  @Nonnull
  public PollActivityTaskQueueResponseOrBuilder getResponse() {
    return response;
  }

  @Nullable
  public LocalActivityArguments getLocalActivityArguments() {
    return localActivityArguments;
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

  @Nullable
  @Override
  public ScalingDecision getScalingDecision() {
    if (!response.hasPollerScalingDecision()) {
      return null;
    }

    return new ScalingTask.ScalingDecision(
        response.getPollerScalingDecision().getPollRequestDeltaSuggestion());
  }
}
