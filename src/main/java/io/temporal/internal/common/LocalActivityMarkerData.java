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

package io.temporal.internal.common;

import io.temporal.common.converter.DataConverter;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.internal.replay.ClockDecisionContext;
import io.temporal.api.workflowservice.v1.RespondActivityTaskCanceledRequest;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import java.time.Duration;
import java.util.Optional;

public final class LocalActivityMarkerData {
  static final String MARKER_RESULT_KEY = "result";
  static final String MARKER_DATA_KEY = "data";

  public static final class Builder {
    private String activityId;
    private String activityType;
    private Optional<Failure> failure = Optional.empty();
    private Optional<Payloads> result = Optional.empty();
    private long replayTimeMillis;
    private int attempt;
    private Duration backoff;

    public Builder setActivityId(String activityId) {
      this.activityId = activityId;
      return this;
    }

    public Builder setActivityType(ActivityType activityType) {
      this.activityType = activityType.getName();
      return this;
    }

    public Builder setTaskFailedRequest(RespondActivityTaskFailedRequest request) {
      this.failure = Optional.of(request.getFailure());
      return this;
    }

    public Builder setTaskCancelledRequest(RespondActivityTaskCanceledRequest request) {
      CanceledFailureInfo.Builder failureInfo = CanceledFailureInfo.newBuilder();
      if (request.hasDetails()) {
        failureInfo.setDetails(request.getDetails());
      }
      this.failure = Optional.of(Failure.newBuilder().setCanceledFailureInfo(failureInfo).build());
      this.result = Optional.empty();
      return this;
    }

    public Builder setResult(Payloads result) {
      this.result = Optional.of(result);
      return this;
    }

    public Builder setFailure(Failure failure) {
      this.failure = Optional.of(failure);
      return this;
    }

    public Builder setReplayTimeMillis(long replayTimeMillis) {
      this.replayTimeMillis = replayTimeMillis;
      return this;
    }

    public Builder setAttempt(int attempt) {
      this.attempt = attempt;
      return this;
    }

    public Builder setBackoff(Duration backoff) {
      this.backoff = backoff;
      return this;
    }

    public LocalActivityMarkerData build() {
      return new LocalActivityMarkerData(
          activityId, activityType, replayTimeMillis, result, failure, attempt, backoff);
    }
  }

  private static class DataValue {
    private String activityId;
    private String activityType;
    private long replayTimeMillis;
    private int attempt;
    private long backoffMillis;

    // Needed by Jackson deserializer
    DataValue() {}

    DataValue(
        String activityId,
        String activityType,
        long replayTimeMillis,
        int attempt,
        Duration backoff) {
      this.activityId = activityId;
      this.activityType = activityType;
      this.replayTimeMillis = replayTimeMillis;
      this.attempt = attempt;
      this.backoffMillis = backoff == null ? 0 : backoff.toMillis();
    }
  }

  private final DataValue data;
  private final Optional<Payloads> result;
  private final Optional<Failure> failure;

  private LocalActivityMarkerData(
      String activityId,
      String activityType,
      long replayTimeMillis,
      Optional<Payloads> result,
      Optional<Failure> failure,
      int attempt,
      Duration backoff) {
    this.data = new DataValue(activityId, activityType, replayTimeMillis, attempt, backoff);
    this.result = result;
    this.failure = failure;
  }

  private LocalActivityMarkerData(
      DataValue data, Optional<Payloads> result, Optional<Failure> failure) {
    this.data = data;
    this.result = result;
    this.failure = failure;
  }

  public String getActivityId() {
    return data.activityId;
  }

  public String getActivityType() {
    return data.activityType;
  }

  public Optional<Failure> getFailure() {
    return failure;
  }

  public Optional<Payloads> getResult() {
    return result;
  }

  public long getReplayTimeMillis() {
    return data.replayTimeMillis;
  }

  public int getAttempt() {
    return data.attempt;
  }

  public Duration getBackoff() {
    return Duration.ofMillis(data.backoffMillis);
  }

  public HistoryEvent toEvent(DataConverter converter) {
    Payloads data = converter.toPayloads(this.data).get();
    MarkerRecordedEventAttributes.Builder attributes =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(ClockDecisionContext.LOCAL_ACTIVITY_MARKER_NAME)
            .putDetails(MARKER_DATA_KEY, data);
    if (result.isPresent()) {
      attributes.putDetails(MARKER_RESULT_KEY, result.get());
    }
    if (failure.isPresent()) {
      attributes.setFailure(failure.get());
    }
    return HistoryEvent.newBuilder()
        .setEventType(EventType.EVENT_TYPE_MARKER_RECORDED)
        .setMarkerRecordedEventAttributes(attributes)
        .build();
  }

  public static LocalActivityMarkerData fromEventAttributes(
      MarkerRecordedEventAttributes attributes, DataConverter converter) {
    Payloads data = attributes.getDetailsOrThrow(MARKER_DATA_KEY);
    DataValue laHeader =
        converter.fromPayloads(Optional.of(data), DataValue.class, DataValue.class);
    Optional<Payloads> result =
        attributes.containsDetails(MARKER_RESULT_KEY)
            ? Optional.of(attributes.getDetailsOrThrow(MARKER_RESULT_KEY))
            : Optional.empty();
    Optional<Failure> failure =
        attributes.hasFailure() ? Optional.of(attributes.getFailure()) : Optional.empty();
    return new LocalActivityMarkerData(laHeader, result, failure);
  }
}
