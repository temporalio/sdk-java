package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.workflow.ChildWorkflowCancellationType;
import javax.annotation.Nullable;

public final class StartChildWorkflowExecutionParameters {

  private final StartChildWorkflowExecutionCommandAttributes.Builder request;
  private final ChildWorkflowCancellationType cancellationType;
  private final UserMetadata metadata;

  public StartChildWorkflowExecutionParameters(
      StartChildWorkflowExecutionCommandAttributes.Builder request,
      ChildWorkflowCancellationType cancellationType,
      @Nullable UserMetadata metadata) {
    this.request = request;
    this.cancellationType = cancellationType;
    this.metadata = metadata;
  }

  public StartChildWorkflowExecutionCommandAttributes.Builder getRequest() {
    return request;
  }

  public ChildWorkflowCancellationType getCancellationType() {
    return cancellationType;
  }

  public @Nullable UserMetadata getMetadata() {
    return metadata;
  }
}
