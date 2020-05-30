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

import com.google.common.base.Strings;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.PayloadConverter;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.common.Header;
import io.temporal.proto.common.Payload;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.event.MarkerRecordedEventAttributes;
import io.temporal.proto.failure.CanceledFailureInfo;
import io.temporal.proto.failure.Failure;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;

import java.time.Duration;
import java.util.Optional;

public final class LocalActivityMarkerData {
  private static final String LOCAL_ACTIVITY_HEADER_KEY = "LocalActivityHeader";

  public static final class Builder {
    private String activityId;
    private String activityType;
    private Optional<Failure> failure;
    private Optional<Payloads> result;
    private long replayTimeMillis;
    private int attempt;
    private Duration backoff;
    private boolean isCancelled;

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

    public Builder setTaskCancelledRequest(
        RespondActivityTaskCanceledRequest request, DataConverter converter) {
      CanceledFailureInfo.Builder failureInfo =
          CanceledFailureInfo.newBuilder().setDetails(request.getDetails());
      if (request.hasDetails()) {
        failureInfo.setDetails(request.getDetails());
      }
      this.failure = Optional.of(Failure.newBuilder().setCanceledFailureInfo(failureInfo).build());
      this.result = Optional.empty();
      this.isCancelled = true;
      return this;
    }

    public Builder setResult(Optional<Payloads> result) {
      this.result = result;
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
          activityId,
          activityType,
          replayTimeMillis,
          result,
          errReason,
          attempt,
          backoff,
          isCancelled);
    }
  }

  private static class LocalActivityMarkerHeader {
    private final String activityId;
    private final String activityType;
    private final String errReason;
    private final long replayTimeMillis;
    private final int attempt;
    private final Duration backoff;
    private final boolean isCancelled;

    LocalActivityMarkerHeader(
        String activityId,
        String activityType,
        long replayTimeMillis,
        String errReason,
        int attempt,
        Duration backoff,
        boolean isCancelled) {
      this.activityId = activityId;
      this.activityType = activityType;
      this.replayTimeMillis = replayTimeMillis;
      this.errReason = errReason;
      this.attempt = attempt;
      this.backoff = backoff;
      this.isCancelled = isCancelled;
    }
  }

  private final LocalActivityMarkerHeader headers;
  private final Optional<Payloads> result;

  private LocalActivityMarkerData(
      String activityId,
      String activityType,
      long replayTimeMillis,
      Optional<Payloads> result,
      String errReason,
      int attempt,
      Duration backoff,
      boolean isCancelled) {
    this.headers =
        new LocalActivityMarkerHeader(
            activityId, activityType, replayTimeMillis, errReason, attempt, backoff, isCancelled);
    this.result = result;
  }

  private LocalActivityMarkerData(LocalActivityMarkerHeader headers, Optional<Payloads> result) {
    this.headers = headers;
    this.result = result;
  }

  public String getActivityId() {
    return headers.activityId;
  }

  public String getActivityType() {
    return headers.activityType;
  }

  public String getErrReason() {
    return headers.errReason;
  }

  public Optional<Payloads> getErrJson() {
    return Strings.isNullOrEmpty(headers.errReason) ? Optional.empty() : result;
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

  public boolean getIsCancelled() {
    return headers.isCancelled;
  }

  public Header getHeader(PayloadConverter converter) {
    Optional<Payload> headerData = converter.toData(headers);
    Header.Builder result = Header.newBuilder();
    if (headerData.isPresent()) {
      result.putFields(LOCAL_ACTIVITY_HEADER_KEY, headerData.get());
    }
    return result.build();
  }

  public static LocalActivityMarkerData fromEventAttributes(
      MarkerRecordedEventAttributes attributes, PayloadConverter converter) {
    Payload payload = attributes.getHeader().getFieldsOrThrow(LOCAL_ACTIVITY_HEADER_KEY);
    LocalActivityMarkerHeader header =
        converter.fromData(
            payload, LocalActivityMarkerHeader.class, LocalActivityMarkerHeader.class);
    Optional<Payloads> details =
        attributes.hasDetails() ? Optional.of(attributes.getDetails()) : Optional.empty();
    return new LocalActivityMarkerData(header, details);
  }
}
