/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.history;

import com.google.common.base.Preconditions;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

public class VersionMarkerUtils {
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
    Map<String, Payloads> detailsMap = event.getMarkerRecordedEventAttributes().getDetailsMap();
    Optional<Payloads> oid = Optional.ofNullable(detailsMap.get(MARKER_CHANGE_ID_KEY));
    String changeId = MarkerUtils.DATA_CONVERTER.fromPayloads(0, oid, String.class, String.class);
    return changeId;
  }

  /**
   * @param event {@code HistoryEvent} to inspect
   * @return true if the event has a correct structure for a version marker
   */
  public static boolean hasVersionMarkerStructure(HistoryEvent event) {
    return MarkerUtils.verifyMarkerName(event, MarkerUtils.VERSION_MARKER_NAME);
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
    details.put(MARKER_CHANGE_ID_KEY, MarkerUtils.DATA_CONVERTER.toPayloads(changeId).get());
    details.put(MARKER_VERSION_KEY, MarkerUtils.DATA_CONVERTER.toPayloads(version).get());
    return RecordMarkerCommandAttributes.newBuilder()
        .setMarkerName(MarkerUtils.VERSION_MARKER_NAME)
        .putAllDetails(details)
        .build();
  }
}
