package io.temporal.internal.worker;

import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.worker.tuning.SlotReleaseReason;
import io.temporal.workflow.Functions;
import javax.annotation.Nonnull;

public class WorkflowTask {
  @Nonnull private final PollWorkflowTaskQueueResponse response;
  @Nonnull private final Functions.Proc1<SlotReleaseReason> completionCallback;

  public WorkflowTask(
      @Nonnull PollWorkflowTaskQueueResponse response,
      @Nonnull Functions.Proc1<SlotReleaseReason> completionCallback) {
    this.response = response;
    this.completionCallback = completionCallback;
  }

  @Nonnull
  public PollWorkflowTaskQueueResponse getResponse() {
    return response;
  }

  /**
   * Completion handle function that must be called by the handler whenever workflow processing is
   * completed.
   */
  @Nonnull
  public Functions.Proc1<SlotReleaseReason> getCompletionCallback() {
    return completionCallback;
  }
}
