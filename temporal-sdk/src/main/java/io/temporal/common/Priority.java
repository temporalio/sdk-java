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

package io.temporal.common;

import java.util.Objects;

/**
 * Priority contains metadata that controls the relative ordering of task processing when tasks are
 * backed up in a queue. The affected queues depend on the server version.
 *
 * <p>Priority is attached to workflows and activities. By default, activities and child workflows
 * inherit Priority from the workflow that created them, but may override fields when an activity is
 * started or modified.
 *
 * <p>For all fields, the field not present or equal to zero/empty string means to inherit the value
 * from the calling workflow, or if there is no calling workflow, then use the default value.
 */
@Experimental
public class Priority {
  public static Priority.Builder newBuilder() {
    return new Priority.Builder(null);
  }

  public static Priority getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final Priority DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = Priority.newBuilder().build();
  }

  public static final class Builder {
    private int priorityKey;

    private Builder(Priority options) {
      if (options == null) {
        return;
      }
      this.priorityKey = options.getPriorityKey();
    }

    /**
     * A priority key is a positive integer from 1 to n, where smaller integers correspond to higher
     * priorities (tasks run sooner). In general, tasks in a queue should be processed in close to
     * priority order, although small deviations are possible.
     *
     * <p>The maximum priority value (minimum priority) is determined by server configuration, and
     * defaults to 5.
     *
     * <p>The default value when unset or 0 is calculated by (min+max)/2. With the default max of 5,
     * and min of 1, that comes out to 3.
     */
    public Builder setPriorityKey(int priorityKey) {
      this.priorityKey = priorityKey;
      return this;
    }

    public Priority build() {
      return new Priority(priorityKey);
    }
  }

  private Priority(int priorityKey) {
    this.priorityKey = priorityKey;
  }

  private final int priorityKey;

  /**
   * See {@link Builder#setPriorityKey(int)}
   *
   * @return The priority key
   */
  public int getPriorityKey() {
    return priorityKey;
  }

  @Override
  public String toString() {
    return "Priority{" + "priorityKey=" + priorityKey + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    Priority priority = (Priority) o;
    return priorityKey == priority.priorityKey;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(priorityKey);
  }
}
