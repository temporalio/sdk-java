package io.temporal.internal.sync;

import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.RawValue;
import io.temporal.workflow.EventGroup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class EventGroupImpl implements EventGroup {
  private final @Nonnull String id;
  private final @Nullable String label;

  EventGroupImpl(@Nonnull String id, @Nullable String label) {
    Preconditions.checkArgument(id != null && !id.isEmpty(), "Event group ID must not be empty");
    this.id = id;
    this.label = label;
  }

  @Nonnull
  @Override
  public String getId() {
    return id;
  }

  @Nullable
  @Override
  public String getLabel() {
    return label;
  }

  EventGroupMarker toMarker(DataConverter dataConverter) {
    EventGroupMarker.Label.Builder labelMarker = EventGroupMarker.Label.newBuilder().setId(id);
    if (label != null) {
      // Converting to json/plain first keeps the label readable by UIs regardless of the
      // user's payload converters, while RawValue still lets the user's codec apply.
      Payload plain =
          DefaultDataConverter.STANDARD_INSTANCE
              .toPayload(label)
              .orElseThrow(() -> new IllegalStateException("Failed to encode event group label"));
      dataConverter.toPayload(new RawValue(plain)).ifPresent(labelMarker::setLabel);
    }
    return EventGroupMarker.newBuilder().setLabel(labelMarker).build();
  }

  @Override
  public String toString() {
    return "EventGroup{" + "id='" + id + '\'' + ", label='" + label + '\'' + '}';
  }

  static List<EventGroupMarker> toMarkers(
      Collection<EventGroup> groups, DataConverter dataConverter) {
    if (groups.isEmpty()) {
      return Collections.emptyList();
    }
    Map<String, EventGroupImpl> byId = new LinkedHashMap<>();
    for (EventGroup group : groups) {
      Preconditions.checkArgument(
          group instanceof EventGroupImpl,
          "Event groups must be created with Workflow.newEventGroup, got %s",
          group);
      byId.putIfAbsent(group.getId(), (EventGroupImpl) group);
    }
    List<EventGroupMarker> markers = new ArrayList<>(byId.size());
    for (EventGroupImpl group : byId.values()) {
      markers.add(group.toMarker(dataConverter));
    }
    return markers;
  }
}
