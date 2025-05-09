package io.temporal.internal.history;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public class VersionMarkerUtils {
  public static final String MARKER_NAME = "Version";
  public static final String MARKER_CHANGE_ID_KEY = "changeId";
  public static final String MARKER_VERSION_KEY = "version";
  public static final String UPSERT_VERSION_SA_KEY = "upsertSA";

  // TemporalChangeVersion is used as search attributes key to find workflows with specific change
  // version.
  @VisibleForTesting
  public static final SearchAttributeKey<List<String>> TEMPORAL_CHANGE_VERSION =
      SearchAttributeKey.forKeywordList("TemporalChangeVersion");

  // Limit the size of the change version search attribute to avoid exceeding the server limits.
  // Exceeding the limit
  // will result in the search attribute not being added.
  public static final int CHANGE_VERSION_SEARCH_ATTRIBUTE_SIZE_LIMIT = 2048;

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

  @Nullable
  public static boolean getUpsertVersionSA(MarkerRecordedEventAttributes markerAttributes) {
    Boolean upsertSA =
        MarkerUtils.getValueFromMarker(markerAttributes, UPSERT_VERSION_SA_KEY, Boolean.class);
    return upsertSA != null && upsertSA;
  }

  public static RecordMarkerCommandAttributes createMarkerAttributes(
      String changeId, Integer version, Boolean upsertVersionSA) {
    Preconditions.checkNotNull(version, "version");
    Map<String, Payloads> details = new HashMap<>();
    details.put(
        MARKER_CHANGE_ID_KEY, DefaultDataConverter.STANDARD_INSTANCE.toPayloads(changeId).get());
    details.put(
        MARKER_VERSION_KEY, DefaultDataConverter.STANDARD_INSTANCE.toPayloads(version).get());
    details.put(
        UPSERT_VERSION_SA_KEY,
        DefaultDataConverter.STANDARD_INSTANCE.toPayloads(upsertVersionSA).get());
    return RecordMarkerCommandAttributes.newBuilder()
        .setMarkerName(MARKER_NAME)
        .putAllDetails(details)
        .build();
  }

  public static String createChangeId(String changeId, Integer version) {
    return changeId + "-" + version;
  }

  public static SearchAttributes createVersionMarkerSearchAttributes(
      String newChangeId, Integer newVersion, Map<String, Integer> existingVersions) {
    List<String> changeVersions = new ArrayList<>(existingVersions.size() + 1);
    existingVersions.entrySet().stream()
        .map(entry -> createChangeId(entry.getKey(), entry.getValue()))
        .forEach(changeVersions::add);
    changeVersions.add(createChangeId(newChangeId, newVersion));
    SearchAttributes sa =
        SearchAttributesUtil.encodeTyped(
            io.temporal.common.SearchAttributes.newBuilder()
                .set(TEMPORAL_CHANGE_VERSION, changeVersions)
                .build());
    return sa;
  }
}
