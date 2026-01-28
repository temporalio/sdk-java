package io.temporal.internal.worker;

import io.temporal.api.workflowservice.v1.PollNexusTaskQueueResponseOrBuilder;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.workflow.Functions;
import javax.annotation.Nonnull;

public final class NexusTask implements ScalingTask {
  private final @Nonnull PollNexusTaskQueueResponseOrBuilder response;
  private final @Nonnull SlotPermit permit;
  private final @Nonnull Functions.Proc completionCallback;

  public NexusTask(
      @Nonnull PollNexusTaskQueueResponseOrBuilder response,
      @Nonnull SlotPermit permit,
      @Nonnull Functions.Proc completionCallback) {
    this.response = response;
    this.permit = permit;
    this.completionCallback = completionCallback;
  }

  @Nonnull
  public PollNexusTaskQueueResponseOrBuilder getResponse() {
    return response;
  }

  /**
   * Completion handle function that must be called by the handler whenever the nexus task
   * processing is completed.
   */
  @Nonnull
  public Functions.Proc getCompletionCallback() {
    return completionCallback;
  }

  @Nonnull
  public SlotPermit getPermit() {
    return permit;
  }

  @Override
  public ScalingDecision getScalingDecision() {
    if (!response.hasPollerScalingDecision()) {
      return null;
    }

    return new ScalingTask.ScalingDecision(
        response.getPollerScalingDecision().getPollRequestDeltaSuggestion());
  }
}
