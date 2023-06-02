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

import java.util.Objects;

/** Options when listing schedules. */
public final class ScheduleListOptions {
  private ScheduleListOptions(int pageSize) {
    this.pageSize = pageSize;
  }

  public static ScheduleListOptions.Builder newBuilder() {
    return new ScheduleListOptions.Builder();
  }

  public static ScheduleListOptions.Builder newBuilder(ScheduleListOptions options) {
    return new ScheduleListOptions.Builder(options);
  }

  public static ScheduleListOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final ScheduleListOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = ScheduleListOptions.newBuilder().build();
  }

  /**
   * Get how many results to fetch from the Server at a time.
   *
   * @return the current page size
   */
  public int getPageSize() {
    return pageSize;
  }

  private final int pageSize;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduleListOptions that = (ScheduleListOptions) o;
    return pageSize == that.pageSize;
  }

  @Override
  public int hashCode() {
    return Objects.hash(pageSize);
  }

  @Override
  public String toString() {
    return "ScheduleListOptions{" + "pageSize=" + pageSize + '}';
  }

  public static class Builder {

    /**
     * Set how many results to fetch from the Server at a time.
     *
     * <p>Default: 100
     */
    public void setPageSize(int pageSize) {
      this.pageSize = pageSize;
    }

    private int pageSize;

    private Builder() {}

    private Builder(ScheduleListOptions options) {
      if (options == null) {
        return;
      }
      pageSize = options.pageSize;
    }

    public ScheduleListOptions build() {
      return new ScheduleListOptions(pageSize == 0 ? 100 : pageSize);
    }
  }
}
