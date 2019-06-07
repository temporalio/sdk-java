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

package com.uber.cadence.internal.common;

import com.uber.cadence.ActivityType;
import com.uber.cadence.RespondActivityTaskCanceledRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class LocalActivityMarkerData {
  public static final class Builder {
    private String activityId;
    private String activityType;
    private String errReason;
    private byte[] errJson;
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
      this.errJson = request.getDetails();
      return this;
    }

    public Builder setTaskCancelledRequest(RespondActivityTaskCanceledRequest request) {
      this.errReason = new String(request.getDetails(), StandardCharsets.UTF_8);
      this.errJson = request.getDetails();
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
          errJson,
          attempt,
          backoff,
          isCancelled);
    }
  }

  private final String activityId;
  private final String activityType;
  private final String errReason;
  private final byte[] errJson;
  private final byte[] result;
  private final long replayTimeMillis;
  private final int attempt;
  private final Duration backoff;
  private final boolean isCancelled;

  private LocalActivityMarkerData(
      String activityId,
      String activityType,
      long replayTimeMillis,
      byte[] result,
      String errReason,
      byte[] errJson,
      int attempt,
      Duration backoff,
      boolean isCancelled) {
    this.activityId = activityId;
    this.activityType = activityType;
    this.replayTimeMillis = replayTimeMillis;
    this.result = result;
    this.errReason = errReason;
    this.errJson = errJson;
    this.attempt = attempt;
    this.backoff = backoff;
    this.isCancelled = isCancelled;
  }

  public String getActivityId() {
    return activityId;
  }

  public String getActivityType() {
    return activityType;
  }

  public String getErrReason() {
    return errReason;
  }

  public byte[] getErrJson() {
    return errJson;
  }

  public byte[] getResult() {
    return result;
  }

  public long getReplayTimeMillis() {
    return replayTimeMillis;
  }

  public int getAttempt() {
    return attempt;
  }

  public Duration getBackoff() {
    return backoff;
  }

  public boolean getIsCancelled() {
    return isCancelled;
  }
}
