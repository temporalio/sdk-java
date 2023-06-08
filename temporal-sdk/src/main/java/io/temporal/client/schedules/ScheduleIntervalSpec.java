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
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Specification for scheduling on an interval. Matching times are expressed as
 *
 * <p>epoch + (n * every) + offset.
 */
public final class ScheduleIntervalSpec {
  private final Duration every;
  private final Duration offset;

  /**
   * Construct a ScheduleIntervalSpec
   *
   * @param every Period to repeat the interval
   */
  public ScheduleIntervalSpec(@Nonnull Duration every) {
    this(every, null);
  }

  /**
   * Construct a ScheduleIntervalSpec
   *
   * @param every Period to repeat the interval
   * @param offset Fixed offset added to each interval period.
   */
  public ScheduleIntervalSpec(@Nonnull Duration every, @Nullable Duration offset) {
    this.every = Preconditions.checkNotNull(every);
    this.offset = offset;
  }

  /**
   * Period to repeat the interval.
   *
   * @return period to repeat
   */
  public @Nonnull Duration getEvery() {
    return every;
  }

  /**
   * Fixed offset added to each interval period.
   *
   * @return offset interval
   */
  public @Nullable Duration getOffset() {
    return offset;
  }

  @Override
  public String toString() {
    return "ScheduleIntervalSpec{" + "every=" + every + ", offset=" + offset + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleIntervalSpec that = (ScheduleIntervalSpec) o;
    return Objects.equals(every, that.every) && Objects.equals(offset, that.offset);
  }

  @Override
  public int hashCode() {
    return Objects.hash(every, offset);
  }
}
