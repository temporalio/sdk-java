/*
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

package com.uber.cadence.internal.replay;

import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.MarkerRecordedEventAttributes;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.workflow.Functions.Func1;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

class MarkerHandler {
  private static final class MarkerResult {

    private final byte[] data;

    /**
     * Count of how many times handle was called since the last marker recorded. It is used to
     * ensure that an updated value is returned after the same exact number of times during a
     * replay.
     */
    private int accessCount;

    private MarkerResult(byte[] data) {
      this.data = data;
    }

    public byte[] getData() {
      accessCount++;
      return data;
    }

    int getAccessCount() {
      return accessCount;
    }
  }

  static final class MarkerData {

    private final String id;
    private final long eventId;
    private final byte[] data;
    private final int accessCount;

    MarkerData(String id, long eventId, byte[] data, int accessCount) {
      this.id = id;
      this.eventId = eventId;
      this.data = data;
      this.accessCount = accessCount;
    }

    public String getId() {
      return id;
    }

    public long getEventId() {
      return eventId;
    }

    public byte[] getData() {
      return data;
    }

    public int getAccessCount() {
      return accessCount;
    }
  }

  private final DecisionsHelper decisions;
  private final String markerName;
  private final ReplayAware replayContext;

  // Key is marker id
  private final Map<String, MarkerResult> mutableMarkerResults = new HashMap<>();

  MarkerHandler(DecisionsHelper decisions, String markerName, ReplayAware replayContext) {
    this.decisions = decisions;
    this.markerName = markerName;
    this.replayContext = replayContext;
  }

  /**
   * @param id marker id
   * @param func given the value from the last marker returns value to store. If result is empty
   *     nothing is recorded into the history.
   * @return the latest value returned by func
   */
  Optional<byte[]> handle(
      String id, DataConverter converter, Func1<Optional<byte[]>, Optional<byte[]>> func) {
    MarkerResult result = mutableMarkerResults.get(id);
    Optional<byte[]> stored;
    if (result == null) {
      stored = Optional.empty();
    } else {
      stored = Optional.of(result.getData());
    }
    long eventId = decisions.getNextDecisionEventId();
    int accessCount = result == null ? 0 : result.getAccessCount();

    if (replayContext.isReplaying()) {
      Optional<byte[]> data = getMarkerDataFromHistory(eventId, id, accessCount, converter);
      if (data.isPresent()) {
        // Need to insert marker to ensure that eventId is incremented
        recordMutableMarker(id, eventId, data.get(), accessCount, converter);
        return data;
      }
      return stored;
    }
    Optional<byte[]> toStore = func.apply(stored);
    if (toStore.isPresent()) {
      byte[] data = toStore.get();
      recordMutableMarker(id, eventId, data, accessCount, converter);
      return toStore;
    }
    return stored;
  }

  private Optional<byte[]> getMarkerDataFromHistory(
      long eventId, String markerId, int expectedAcccessCount, DataConverter converter) {
    HistoryEvent event = decisions.getDecisionEvent(eventId);
    if (event.getEventType() != EventType.MarkerRecorded) {
      return Optional.empty();
    }
    MarkerRecordedEventAttributes attributes = event.getMarkerRecordedEventAttributes();
    String name = attributes.getMarkerName();
    if (!markerName.equals(name)) {
      return Optional.empty();
    }
    MarkerData markerData = converter.fromData(attributes.getDetails(), MarkerData.class);
    // access count is used to not return data from the marker before the recorded number of calls
    if (!markerId.equals(markerData.getId())
        || markerData.getAccessCount() > expectedAcccessCount) {
      return Optional.empty();
    }
    return Optional.of(markerData.getData());
  }

  private void recordMutableMarker(
      String id, long eventId, byte[] data, int accessCount, DataConverter converter) {
    MarkerData dataObject = new MarkerData(id, eventId, data, accessCount);
    byte[] details = converter.toData(dataObject);
    mutableMarkerResults.put(id, new MarkerResult(data));
    decisions.recordMarker(markerName, details);
  }
}
