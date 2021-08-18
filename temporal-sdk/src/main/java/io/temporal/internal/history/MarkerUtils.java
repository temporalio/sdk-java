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

import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;

public class MarkerUtils {
  public static final String VERSION_MARKER_NAME = "Version";

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
}
