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

package io.temporal.client.schedules;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import java.time.Instant;
import java.util.Objects;

/** Time period and policy for actions taken as if their scheduled time has already passed. */
public final class ScheduleBackfill {
  private final Instant startAt;
  private final Instant endAt;
  private final ScheduleOverlapPolicy overlapPolicy;

  /**
   * Create a backfill request.
   *
   * @param startAt Start of the range to evaluate the schedule in. This is exclusive.
   * @param endAt End of the range to evaluate the schedule in. This is inclusive.
   */
  public ScheduleBackfill(Instant startAt, Instant endAt) {
    this(startAt, endAt, ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_UNSPECIFIED);
  }

  /**
   * Create a backfill request.
   *
   * @param startAt Start of the range to evaluate the schedule in. This is exclusive.
   * @param endAt End of the range to evaluate the schedule in. This is inclusive.
   * @param overlapPolicy Overlap policy to use for this backfill request.
   */
  public ScheduleBackfill(Instant startAt, Instant endAt, ScheduleOverlapPolicy overlapPolicy) {
    this.startAt = startAt;
    this.endAt = endAt;
    this.overlapPolicy = overlapPolicy;
  }

  /**
   * Get the start of the range in this request. This is exclusive.
   *
   * @return start of range
   */
  public Instant getStartAt() {
    return startAt;
  }

  /**
   * End of the range to evaluate the schedule in this request. This is inclusive.
   *
   * @return end of range
   */
  public Instant getEndAt() {
    return endAt;
  }

  /**
   * Get the overlap policy for this request
   *
   * @return overlap policy
   */
  public ScheduleOverlapPolicy getOverlapPolicy() {
    return overlapPolicy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleBackfill that = (ScheduleBackfill) o;
    return Objects.equals(startAt, that.startAt)
        && Objects.equals(endAt, that.endAt)
        && overlapPolicy == that.overlapPolicy;
  }

  @Override
  public int hashCode() {
    return Objects.hash(startAt, endAt, overlapPolicy);
  }

  @Override
  public String toString() {
    return "ScheduleBackfill{"
        + "startAt="
        + startAt
        + ", endAt="
        + endAt
        + ", overlapPolicy="
        + overlapPolicy
        + '}';
  }
}
