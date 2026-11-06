package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.StartChildWorkflowExecutionCommandAttributes;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.workflow.ChildWorkflowCancellationType;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

public final class StartChildWorkflowExecutionParameters {

  private final StartChildWorkflowExecutionCommandAttributes.Builder request;
  private final ChildWorkflowCancellationType cancellationType;
  private final UserMetadata metadata;
  private final List<EventGroupMarker> eventGroupMarkers;

  public StartChildWorkflowExecutionParameters(
      StartChildWorkflowExecutionCommandAttributes.Builder request,
      ChildWorkflowCancellationType cancellationType,
      @Nullable UserMetadata metadata,
      List<EventGroupMarker> eventGroupMarkers) {
    this.request = request;
    this.cancellationType = cancellationType;
    this.metadata = metadata;
    this.eventGroupMarkers = Objects.requireNonNull(eventGroupMarkers);
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

  public List<EventGroupMarker> getEventGroupMarkers() {
    return eventGroupMarkers;
  }
}
