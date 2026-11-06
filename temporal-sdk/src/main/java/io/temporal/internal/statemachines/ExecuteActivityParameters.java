package io.temporal.internal.statemachines;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.api.sdk.v1.UserMetadata;
import java.util.List;
import java.util.Objects;

public class ExecuteActivityParameters {

  private final ScheduleActivityTaskCommandAttributes.Builder attributes;
  private final ActivityCancellationType cancellationType;
  private final UserMetadata metadata;
  private final List<EventGroupMarker> eventGroupMarkers;

  public ExecuteActivityParameters(
      ScheduleActivityTaskCommandAttributes.Builder attributes,
      ActivityCancellationType cancellationType,
      UserMetadata metadata,
      List<EventGroupMarker> eventGroupMarkers) {
    this.attributes = Objects.requireNonNull(attributes);
    this.cancellationType = Objects.requireNonNull(cancellationType);
    this.metadata = metadata;
    this.eventGroupMarkers = Objects.requireNonNull(eventGroupMarkers);
  }

  public ScheduleActivityTaskCommandAttributes.Builder getAttributes() {
    return attributes;
  }

  public ActivityCancellationType getCancellationType() {
    return cancellationType;
  }

  public UserMetadata getMetadata() {
    return metadata;
  }

  public List<EventGroupMarker> getEventGroupMarkers() {
    return eventGroupMarkers;
  }
}
