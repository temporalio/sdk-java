package io.temporal.internal.history;

import com.google.common.base.Preconditions;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public class VersionMarkerUtils {
  public static final String MARKER_NAME = "Version";
  public static final String MARKER_CHANGE_ID_KEY = "changeId";
  public static final String MARKER_VERSION_KEY = "version";

  /**
   * @param event {@code HistoryEvent} to parse
   * @return changeId of this version marker if the structure of this event looks like a marker
   *     event, {@code null} otherwise
   */
  @Nullable
  public static String tryGetChangeIdFromVersionMarkerEvent(HistoryEvent event) {
    if (!hasVersionMarkerStructure(event)) {
      return null;
    }
    return getChangeId(event.getMarkerRecordedEventAttributes());
  }

  /**
   * @param event {@code HistoryEvent} to inspect
   * @return true if the event has a correct structure for a version marker
   */
  public static boolean hasVersionMarkerStructure(HistoryEvent event) {
    return MarkerUtils.verifyMarkerName(event, MARKER_NAME);
  }

  /**
   * @param command {@code Command} to inspect
   * @return true if the command has a correct structure for a version marker
   */
  public static boolean hasVersionMarkerStructure(Command command) {
    return MarkerUtils.verifyMarkerName(command, MARKER_NAME);
  }

  @Nullable
  public static String getChangeId(MarkerRecordedEventAttributes markerAttributes) {
    return MarkerUtils.getValueFromMarker(markerAttributes, MARKER_CHANGE_ID_KEY, String.class);
  }

  @Nullable
  public static Integer getVersion(MarkerRecordedEventAttributes markerAttributes) {
    return MarkerUtils.getValueFromMarker(markerAttributes, MARKER_VERSION_KEY, Integer.class);
  }

  public static RecordMarkerCommandAttributes createMarkerAttributes(
      String changeId, Integer version) {
    Preconditions.checkNotNull(version, "version");
    Map<String, Payloads> details = new HashMap<>();
    details.put(
        MARKER_CHANGE_ID_KEY, DefaultDataConverter.STANDARD_INSTANCE.toPayloads(changeId).get());
    details.put(
        MARKER_VERSION_KEY, DefaultDataConverter.STANDARD_INSTANCE.toPayloads(version).get());
    return RecordMarkerCommandAttributes.newBuilder()
        .setMarkerName(MARKER_NAME)
        .putAllDetails(details)
        .build();
  }
}
