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

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import javax.annotation.Nullable;

/**
 * See <a
 * href="https://github.com/temporalio/sdk-core/blob/master/protos/local/temporal/sdk/core/external_data/external_data.proto#L12">Core
 * Data Structure</a>
 */
public class LocalActivityMarkerMetadata {
  // The time the LA was originally scheduled (wall clock time). This is used to track
  // schedule-to-close timeouts when timer-based backoffs are used.
  @JsonProperty(value = "firstSkd")
  private long originalScheduledTimestamp;

  // The number of attempts at execution before we recorded this result. Typically starts at 1,
  // but it is possible to start at a higher number when backing off using a timer.
  @JsonProperty(value = "atpt")
  private int attempt;

  // If set, this local activity conceptually is retrying after the specified backoff.
  // Implementation wise, they are really two different LA machines, but with the same type & input.
  // The retry starts with an attempt number > 1.
  @Nullable
  @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
  @JsonProperty(value = "backoff")
  private Duration backoff;

  public LocalActivityMarkerMetadata() {}

  public LocalActivityMarkerMetadata(int attempt, long originalScheduledTimestamp) {
    this.attempt = attempt;
    this.originalScheduledTimestamp = originalScheduledTimestamp;
  }

  public long getOriginalScheduledTimestamp() {
    return originalScheduledTimestamp;
  }

  public void setOriginalScheduledTimestamp(long originalScheduledTimestamp) {
    this.originalScheduledTimestamp = originalScheduledTimestamp;
  }

  public int getAttempt() {
    return attempt;
  }

  public void setAttempt(int attempt) {
    this.attempt = attempt;
  }

  @Nullable
  public Duration getBackoff() {
    return backoff;
  }

  public void setBackoff(@Nullable Duration backoff) {
    this.backoff = backoff;
  }
}
