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

package io.temporal.internal.replay;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.sync.WorkflowInternal;
import io.temporal.workflow.Functions.Func1;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

class MarkerHandler {
  // Including mutable side effect and version marker.
  static final String MUTABLE_MARKER_HEADER_KEY = "header";
  static final String MUTABLE_MARKER_DATA_KEY = "data";

  private static final class MarkerResult {

    private final Optional<Payloads> data;

    /**
     * Count of how many times handle was called since the last marker recorded. It is used to
     * ensure that an updated value is returned after the same exact number of times during a
     * replay.
     */
    private int accessCount;

    private MarkerResult(Optional<Payloads> data) {
      this.data = data;
    }

    public Optional<Payloads> getData() {
      accessCount++;
      return data;
    }

    int getAccessCount() {
      return accessCount;
    }
  }

  static final class MarkerData {

    private static final class MarkerHeader {
      private String id;
      private long eventId;
      private int accessCount;

      // Needed for Jackson deserialization
      MarkerHeader() {}

      MarkerHeader(String id, long eventId, int accessCount) {
        this.id = id;
        this.eventId = eventId;
        this.accessCount = accessCount;
      }
    }

    private final MarkerHeader header;
    private final Optional<Payloads> data;

    static MarkerData fromEventAttributes(
        MarkerRecordedEventAttributes attributes, DataConverter converter) {
      Optional<Payloads> details =
          attributes.containsDetails(MUTABLE_MARKER_DATA_KEY)
              ? Optional.of(attributes.getDetailsOrThrow(MUTABLE_MARKER_DATA_KEY))
              : Optional.empty();
      MarkerData.MarkerHeader header =
          converter.fromPayloads(
              Optional.of(attributes.getDetailsOrThrow(MUTABLE_MARKER_HEADER_KEY)),
              MarkerData.MarkerHeader.class,
              MarkerData.MarkerHeader.class);

      return new MarkerData(header, details);
    }

    MarkerData(String id, long eventId, Optional<Payloads> data, int accessCount) {
      this.header = new MarkerHeader(id, eventId, accessCount);
      this.data = Objects.requireNonNull(data);
    }

    MarkerData(MarkerHeader header, Optional<Payloads> data) {
      this.header = header;
      this.data = Objects.requireNonNull(data);
    }

    public MarkerHeader getHeader() {
      return header;
    }

    public String getId() {
      return header.id;
    }

    public long getEventId() {
      return header.eventId;
    }

    public int getAccessCount() {
      return header.accessCount;
    }

    public Optional<Payloads> getData() {
      return data;
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
  Optional<Payloads> handle(
      String id, DataConverter converter, Func1<Optional<Payloads>, Optional<Payloads>> func) {
    MarkerResult result = mutableMarkerResults.get(id);
    Optional<Payloads> stored;
    if (result == null) {
      stored = Optional.empty();
    } else {
      stored = result.getData();
    }
    long eventId = decisions.getNextDecisionEventId();
    int accessCount = result == null ? 0 : result.getAccessCount();

    if (replayContext.isReplaying()) {
      Optional<Payloads> data = getMarkerDataFromHistory(eventId, id, accessCount, converter);
      if (data.isPresent()) {
        // Need to insert marker to ensure that eventId is incremented
        recordMutableMarker(id, eventId, data, accessCount, converter);
        return data;
      }

      // TODO(maxim): Verify why this is necessary.
      if (!stored.isPresent()) {
        mutableMarkerResults.put(
            id, new MarkerResult(converter.toPayloads(WorkflowInternal.DEFAULT_VERSION)));
      }

      return stored;
    }
    Optional<Payloads> toStore = func.apply(stored);
    if (toStore.isPresent()) {
      recordMutableMarker(id, eventId, toStore, accessCount, converter);
      return toStore;
    }
    return stored;
  }

  private Optional<Payloads> getMarkerDataFromHistory(
      long eventId, String markerId, int expectedAcccessCount, DataConverter converter) {
    Optional<HistoryEvent> event = decisions.getOptionalDecisionEvent(eventId);
    if (!event.isPresent() || event.get().getEventType() != EventType.EVENT_TYPE_MARKER_RECORDED) {
      return Optional.empty();
    }

    MarkerRecordedEventAttributes attributes = event.get().getMarkerRecordedEventAttributes();
    String name = attributes.getMarkerName();
    if (!markerName.equals(name)) {
      return Optional.empty();
    }

    MarkerData markerData = MarkerData.fromEventAttributes(attributes, converter);
    // access count is used to not return data from the marker before the recorded number of calls
    if (!markerId.equals(markerData.getId())
        || markerData.getAccessCount() > expectedAcccessCount) {
      return Optional.empty();
    }
    return markerData.getData();
  }

  private void recordMutableMarker(
      String id, long eventId, Optional<Payloads> data, int accessCount, DataConverter converter) {
    MarkerData marker = new MarkerData(id, eventId, data, accessCount);
    mutableMarkerResults.put(id, new MarkerResult(data));
    Map<String, Payloads> details = new HashMap<>();
    if (data.isPresent()) {
      details.put(MUTABLE_MARKER_DATA_KEY, data.get());
    }
    details.put(MUTABLE_MARKER_HEADER_KEY, converter.toPayloads(marker.getHeader()).get());
    decisions.recordMarker(markerName, Optional.empty(), details, Optional.empty());
  }
}
