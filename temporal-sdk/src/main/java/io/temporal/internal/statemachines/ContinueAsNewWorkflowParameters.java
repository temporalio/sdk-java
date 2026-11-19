package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.sdk.v1.EventGroupMarker;
import java.util.List;
import java.util.Objects;

public final class ContinueAsNewWorkflowParameters {

  private final ContinueAsNewWorkflowExecutionCommandAttributes attributes;
  private final List<EventGroupMarker> eventGroupMarkers;

  public ContinueAsNewWorkflowParameters(
      ContinueAsNewWorkflowExecutionCommandAttributes attributes,
      List<EventGroupMarker> eventGroupMarkers) {
    this.attributes = Objects.requireNonNull(attributes);
    this.eventGroupMarkers = Objects.requireNonNull(eventGroupMarkers);
  }

  public ContinueAsNewWorkflowExecutionCommandAttributes getAttributes() {
    return attributes;
  }

  public List<EventGroupMarker> getEventGroupMarkers() {
    return eventGroupMarkers;
  }
}
