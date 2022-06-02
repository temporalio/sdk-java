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
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.common.converter.DataConverter;
import java.util.Optional;

public class MarkerUtils {
  public static final String VERSION_MARKER_NAME = "Version";
  public static final DataConverter DATA_CONVERTER = DataConverter.getDefaultInstance();

  /**
   * @param event {@code HistoryEvent} to inspect
   * @param markerName expected marker name
   * @return true if the event has a correct structure for a marker and an expected marker name
   */
  public static boolean verifyMarkerName(HistoryEvent event, String markerName) {
    if (!EventType.EVENT_TYPE_MARKER_RECORDED.equals(event.getEventType())) {
      return false;
    }
    MarkerRecordedEventAttributes attributes = event.getMarkerRecordedEventAttributes();
    return markerName.equals(attributes.getMarkerName());
  }

  /**
   * @param markerAttributes
   * @param key
   * @param simpleValueType
   * @param <T>
   * @return
   */
  public static <T> T getValueFromMarker(
      MarkerRecordedEventAttributes markerAttributes, String key, Class<T> simpleValueType) {
    Optional<Payloads> payloads = Optional.ofNullable(markerAttributes.getDetailsMap().get(key));
    return DATA_CONVERTER.fromPayloads(0, payloads, simpleValueType, simpleValueType);
  }
}
