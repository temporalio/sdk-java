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
