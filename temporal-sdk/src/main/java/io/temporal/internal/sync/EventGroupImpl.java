package io.temporal.internal.sync;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.RawValue;
import io.temporal.workflow.EventGroup;
import io.temporal.workflow.Functions;
import java.util.Collections;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class EventGroupImpl implements EventGroup {
  private final String id;
  private final @Nullable String label;

  EventGroupImpl(@Nonnull String id, @Nullable String label) {
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

  @Override
  public void run(Functions.Proc proc) {
    run(
        () -> {
          proc.apply();
          return null;
        });
  }

  @Override
  public <R> R run(Functions.Func<R> func) {
    return EventGroupScope.current().withExplicitGroups(Collections.singletonList(this)).run(func);
  }

  /**
   * @param dataConverter converter used to encode the label, including any payload codec
   */
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
}
