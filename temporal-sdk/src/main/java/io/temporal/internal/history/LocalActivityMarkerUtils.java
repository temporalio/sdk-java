package io.temporal.internal.history;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import java.util.Map;
import javax.annotation.Nullable;

public class LocalActivityMarkerUtils {
  public static final String MARKER_NAME = "LocalActivity";
  public static final String MARKER_ACTIVITY_ID_KEY = "activityId";
  public static final String MARKER_ACTIVITY_TYPE_KEY = "type";
  public static final String MARKER_ACTIVITY_RESULT_KEY = "result";
  public static final String MARKER_ACTIVITY_INPUT_KEY = "input";
  public static final String MARKER_TIME_KEY = "time";
  public static final String MARKER_METADATA_KEY = "meta";
  // Deprecated in favor of result. Still present for backwards compatibility.
  private static final String MARKER_DATA_KEY = "data";

  /**
   * @param event {@code HistoryEvent} to inspect
   * @return true if the event has a correct structure for a local activity
   */
  public static boolean hasLocalActivityStructure(HistoryEvent event) {
    return MarkerUtils.verifyMarkerName(event, MARKER_NAME);
  }

  @Nullable
  public static String getActivityId(MarkerRecordedEventAttributes markerAttributes) {
    return MarkerUtils.getValueFromMarker(markerAttributes, MARKER_ACTIVITY_ID_KEY, String.class);
  }

  @Nullable
  public static String getActivityTypeName(MarkerRecordedEventAttributes markerAttributes) {
    return MarkerUtils.getValueFromMarker(markerAttributes, MARKER_ACTIVITY_TYPE_KEY, String.class);
  }

  @Nullable
  public static Payloads getResult(MarkerRecordedEventAttributes markerAttributes) {
    Map<String, Payloads> detailsMap = markerAttributes.getDetailsMap();
    Payloads result = detailsMap.get(LocalActivityMarkerUtils.MARKER_ACTIVITY_RESULT_KEY);
    if (result == null) {
      // Support old histories that used "data" as a key for "result".
      result = detailsMap.get(LocalActivityMarkerUtils.MARKER_DATA_KEY);
    }
    return result;
  }

  @Nullable
  public static Long getTime(MarkerRecordedEventAttributes markerAttributes) {
    return MarkerUtils.getValueFromMarker(markerAttributes, MARKER_TIME_KEY, Long.class);
  }

  @Nullable
  public static LocalActivityMarkerMetadata getMetadata(
      MarkerRecordedEventAttributes markerAttributes) {
    return MarkerUtils.getValueFromMarker(
        markerAttributes, MARKER_METADATA_KEY, LocalActivityMarkerMetadata.class);
  }
}
