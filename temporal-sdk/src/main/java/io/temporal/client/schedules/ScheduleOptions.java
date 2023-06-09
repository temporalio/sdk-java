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

import java.util.List;
import java.util.Map;

/** Options for creating a schedule. */
public final class ScheduleOptions {
  public static ScheduleOptions.Builder newBuilder() {
    return new ScheduleOptions.Builder();
  }

  public static ScheduleOptions.Builder newBuilder(ScheduleOptions options) {
    return new ScheduleOptions.Builder(options);
  }

  public static final class Builder {
    private boolean triggerImmediately;
    private List<ScheduleBackfill> backfills;
    private Map<String, Object> memo;
    private Map<String, ?> searchAttributes;

    private Builder() {}

    private Builder(ScheduleOptions options) {
      if (options == null) {
        return;
      }
      this.triggerImmediately = options.triggerImmediately;
      this.backfills = options.backfills;
      this.memo = options.memo;
      this.searchAttributes = options.searchAttributes;
    }

    /** Set if the schedule will be triggered immediately upon creation. */
    public Builder setTriggerImmediately(boolean triggerImmediately) {
      this.triggerImmediately = triggerImmediately;
      return this;
    }

    /** Set the time periods to take actions on as if that time passed right now. */
    public Builder setBackfills(List<ScheduleBackfill> backfills) {
      this.backfills = backfills;
      return this;
    }

    /** Set the memo for the schedule. Values for the memo cannot be null. */
    public Builder setMemo(Map<String, Object> memo) {
      this.memo = memo;
      return this;
    }

    /** Set the search attributes for the schedule. */
    public Builder setSearchAttributes(Map<String, ?> searchAttributes) {
      this.searchAttributes = searchAttributes;
      return this;
    }

    public ScheduleOptions build() {
      return new ScheduleOptions(triggerImmediately, backfills, memo, searchAttributes);
    }
  }

  private final boolean triggerImmediately;
  private final List<ScheduleBackfill> backfills;
  private final Map<String, Object> memo;
  private final Map<String, ?> searchAttributes;

  private ScheduleOptions(
      boolean triggerImmediately,
      List<ScheduleBackfill> backfills,
      Map<String, Object> memo,
      Map<String, ?> searchAttributes) {
    this.triggerImmediately = triggerImmediately;
    this.backfills = backfills;
    this.memo = memo;
    this.searchAttributes = searchAttributes;
  }

  /**
   * Get if the schedule will be triggered immediately upon creation.
   *
   * @return True if the schedule will trigger on creation
   */
  public boolean isTriggerImmediately() {
    return triggerImmediately;
  }

  /**
   * Get the time periods to take actions on as if that time passed right now.
   *
   * @return backfill requests
   */
  public List<ScheduleBackfill> getBackfills() {
    return backfills;
  }

  /**
   * Get the memo for the schedule. Values for the memo cannot be null.
   *
   * @return memos for the schedule
   */
  public Map<String, Object> getMemo() {
    return memo;
  }

  /**
   * Get the search attributes for the schedule.
   *
   * @return search attributes for the schedule
   */
  public Map<String, ?> getSearchAttributes() {
    return searchAttributes;
  }
}
