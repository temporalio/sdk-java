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

import com.google.common.base.Preconditions;
import java.util.Objects;

/** Inclusive range for a schedule match value. */
public final class ScheduleRange {
  private final int start;
  private final int end;
  private final int step;

  /**
   * Create a inclusive range for a schedule match value.
   *
   * @param start The inclusive start of the range
   */
  public ScheduleRange(int start) {
    this(start, 0, 0);
  }

  /**
   * Create a inclusive range for a schedule match value.
   *
   * @param start The inclusive start of the range
   * @param end The inclusive end of the range. Default if unset or less than start is start.
   */
  public ScheduleRange(int start, int end) {
    this(start, end, 0);
  }

  /**
   * Create a inclusive range for a schedule match value.
   *
   * @param start The inclusive start of the range
   * @param end The inclusive end of the range. Default if unset or less than start is start.
   * @param step The step to take between each value. Default if unset or 0, is 1.
   */
  public ScheduleRange(int start, int end, int step) {
    Preconditions.checkState(start >= 0 && step >= 0 && step >= 0);
    this.start = start;
    this.end = end;
    this.step = step;
  }

  /**
   * Gets the inclusive start of the range.
   *
   * @return start of range
   */
  public int getStart() {
    return start;
  }

  /**
   * Gets the inclusive end of the range.
   *
   * @return end of range
   */
  public int getEnd() {
    return end;
  }

  /**
   * Gets the step taken between each value.
   *
   * @return steps taken between values.
   */
  public int getStep() {
    return step;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleRange that = (ScheduleRange) o;
    return start == that.start && end == that.end && step == that.step;
  }

  @Override
  public int hashCode() {
    return Objects.hash(start, end, step);
  }

  @Override
  public String toString() {
    return "ScheduleRange{" + "start=" + start + ", end=" + end + ", step=" + step + '}';
  }
}
