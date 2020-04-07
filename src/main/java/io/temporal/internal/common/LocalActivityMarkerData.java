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
import com.google.protobuf.ByteString;
import io.temporal.common.converter.DataConverter;
import io.temporal.proto.common.ActivityType;
import io.temporal.proto.common.Header;
import io.temporal.proto.event.MarkerRecordedEventAttributes;
import io.temporal.proto.workflowservice.RespondActivityTaskCanceledRequest;
import io.temporal.proto.workflowservice.RespondActivityTaskFailedRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class LocalActivityMarkerData {
  private static final String LOCAL_ACTIVITY_HEADER_KEY = "LocalActivityHeader";

  public static final class Builder {
    private String activityId;
    private String activityType;
    private String errReason;
    private byte[] result;
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
      this.errReason = request.getReason();
      this.result = request.getDetails().toByteArray();
      return this;
    }

    public Builder setTaskCancelledRequest(RespondActivityTaskCanceledRequest request) {
      this.errReason = request.getDetails().toString(StandardCharsets.UTF_8);
      this.result = request.getDetails().toByteArray();
      this.isCancelled = true;
      return this;
    }

    public Builder setResult(byte[] result) {
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
  private final byte[] result;

  private LocalActivityMarkerData(
      String activityId,
      String activityType,
      long replayTimeMillis,
      byte[] result,
      String errReason,
      int attempt,
      Duration backoff,
      boolean isCancelled) {
    this.headers =
        new LocalActivityMarkerHeader(
            activityId, activityType, replayTimeMillis, errReason, attempt, backoff, isCancelled);
    this.result = result;
  }

  private LocalActivityMarkerData(LocalActivityMarkerHeader headers, byte[] result) {
    this.headers = headers;
    this.result = result;
    if (headers == null) {
      System.out.println("test");
    }
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

  public byte[] getErrJson() {
    return Strings.isNullOrEmpty(headers.errReason) ? null : result;
  }

  public byte[] getResult() {
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

  public Header getHeader(DataConverter converter) {
    byte[] headerData = converter.toData(headers);
    return Header.newBuilder()
        .putFields(LOCAL_ACTIVITY_HEADER_KEY, ByteString.copyFrom(headerData))
        .build();
  }

  public static LocalActivityMarkerData fromEventAttributes(
      MarkerRecordedEventAttributes attributes, DataConverter converter) {
    ByteString bytes = attributes.getHeader().getFieldsOrThrow(LOCAL_ACTIVITY_HEADER_KEY);
    LocalActivityMarkerHeader header =
        converter.fromData(
            bytes.toByteArray(), LocalActivityMarkerHeader.class, LocalActivityMarkerHeader.class);
    return new LocalActivityMarkerData(header, attributes.getDetails().toByteArray());
  }
}
