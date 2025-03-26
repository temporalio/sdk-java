/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.internal.history;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class VersionMarkerUtils {
  public static final String MARKER_NAME = "Version";
  public static final String MARKER_CHANGE_ID_KEY = "changeId";
  public static final String MARKER_VERSION_KEY = "version";
  public static final String UPSERT_VERSION_SA_KEY = "upsertSA";
  // TemporalChangeVersion is used as search attributes key to find workflows with specific change
  // version.
  private static final SearchAttributeKey<List<String>> TEMPORAL_CHANGE_VERSION =
      SearchAttributeKey.forKeywordList("TemporalChangeVersion");

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
  public static Boolean getUpsertVersionSA(MarkerRecordedEventAttributes markerAttributes) {
    return MarkerUtils.getValueFromMarker(markerAttributes, UPSERT_VERSION_SA_KEY, Boolean.class);
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

  public static SearchAttributes createVersionMarkerSearchAttributes(
      Map<String, Integer> existingVersions) {
    List<String> changeVersions =
        existingVersions.entrySet().stream()
            .map(entry -> entry.getKey() + "-" + entry.getValue())
            .collect(Collectors.toList());
    return SearchAttributesUtil.encodeTyped(
        io.temporal.common.SearchAttributes.newBuilder()
            .set(TEMPORAL_CHANGE_VERSION, changeVersions)
            .build());
  }
}
