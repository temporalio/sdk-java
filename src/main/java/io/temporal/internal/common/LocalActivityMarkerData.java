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
import io.temporal.common.converter.PayloadConverter;
import io.temporal.internal.replay.ClockDecisionContext;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.common.Header;
import io.temporal.proto.common.Payload;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.event.EventType;
import io.temporal.proto.event.HistoryEvent;
import io.temporal.proto.event.MarkerRecordedEventAttributes;
import io.temporal.proto.failure.CanceledFailureInfo;
import io.temporal.proto.failure.Failure;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;
import java.time.Duration;
import java.util.Optional;

public final class LocalActivityMarkerData {
  private static final String LOCAL_ACTIVITY_HEADER_KEY = "LocalActivityHeader";
  private static final String LOCAL_ACTIVITY_FAILURE_KEY = "LocalActivityFailure";

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
      this.activityType = activityType.toString();
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

  private static class LocalActivityMarkerHeader {
    private final String activityId;
    private final String activityType;
    private final long replayTimeMillis;
    private final int attempt;
    private final Duration backoff;

    LocalActivityMarkerHeader(
        String activityId,
        String activityType,
        long replayTimeMillis,
        int attempt,
        Duration backoff) {
      this.activityId = activityId;
      this.activityType = activityType;
      this.replayTimeMillis = replayTimeMillis;
      this.attempt = attempt;
      this.backoff = backoff;
    }
  }

  private final LocalActivityMarkerHeader headers;
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
    this.headers =
        new LocalActivityMarkerHeader(activityId, activityType, replayTimeMillis, attempt, backoff);
    this.result = result;
    this.failure = failure;
  }

  private LocalActivityMarkerData(
      LocalActivityMarkerHeader headers, Optional<Payloads> result, Optional<Failure> failure) {
    this.headers = headers;
    this.result = result;
    this.failure = failure;
  }

  public String getActivityId() {
    return headers.activityId;
  }

  public String getActivityType() {
    return headers.activityType;
  }

  public Optional<Failure> getFailure() {
    return failure;
  }

  public Optional<Payloads> getResult() {
    return result;
  }

  public long getReplayTimeMillis() {
    return headers.replayTimeMillis;
  }

  public int getAttempt() {
    return headers.attempt;
  }

  public Duration getBackoff() {
    return headers.backoff;
  }

  public Header getHeader(PayloadConverter converter) {
    Optional<Payload> headerData = converter.toData(headers);
    Header.Builder result = Header.newBuilder();
    if (headerData.isPresent()) {
      result.putFields(LOCAL_ACTIVITY_HEADER_KEY, headerData.get());
    }
    if (failure.isPresent()) {
      Optional<Payload> failureData = converter.toData(failure.get());
      if (failureData.isPresent()) {
        result.putFields(LOCAL_ACTIVITY_FAILURE_KEY, failureData.get());
      }
    }
    return result.build();
  }

  public HistoryEvent toEvent(DataConverter converter) {
    MarkerRecordedEventAttributes.Builder attributes =
        MarkerRecordedEventAttributes.newBuilder()
            .setMarkerName(ClockDecisionContext.LOCAL_ACTIVITY_MARKER_NAME)
            .setHeader(getHeader(converter.getPayloadConverter()));
    if (result.isPresent()) {
      attributes.setDetails(result.get());
    }
    return HistoryEvent.newBuilder()
        .setEventType(EventType.MarkerRecorded)
        .setMarkerRecordedEventAttributes(attributes)
        .build();
  }

  public static LocalActivityMarkerData fromEventAttributes(
      MarkerRecordedEventAttributes attributes, PayloadConverter converter) {
    Header header = attributes.getHeader();
    Payload payload = header.getFieldsOrThrow(LOCAL_ACTIVITY_HEADER_KEY);
    LocalActivityMarkerHeader laHeader =
        converter.fromData(
            payload, LocalActivityMarkerHeader.class, LocalActivityMarkerHeader.class);
    Optional<Payloads> details =
        attributes.hasDetails() ? Optional.of(attributes.getDetails()) : Optional.empty();
    Optional<Failure> failure = Optional.empty();
    if (header.containsFields(LOCAL_ACTIVITY_FAILURE_KEY)) {
      Payload failurePayload = header.getFieldsOrThrow(LOCAL_ACTIVITY_FAILURE_KEY);
      failure = Optional.of(converter.fromData(failurePayload, Failure.class, Failure.class));
    }
    return new LocalActivityMarkerData(laHeader, details, failure);
  }
}
