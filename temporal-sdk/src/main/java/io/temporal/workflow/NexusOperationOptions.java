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

package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.time.Duration;
import java.util.Objects;

/**
 * NexusOperationOptions is used to specify the options for starting a Nexus operation from a
 * Workflow.
 *
 * <p>Use {@link NexusOperationOptions#newBuilder()} to construct an instance.
 */
@Experimental
public final class NexusOperationOptions {
  public static NexusOperationOptions.Builder newBuilder() {
    return new NexusOperationOptions.Builder();
  }

  public static NexusOperationOptions.Builder newBuilder(NexusOperationOptions options) {
    return new NexusOperationOptions.Builder(options);
  }

  public static NexusOperationOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final NexusOperationOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = NexusOperationOptions.newBuilder().build();
  }

  public static final class Builder {
    private Duration scheduleToCloseTimeout;

    /**
     * Sets the schedule to close timeout for the Nexus operation.
     *
     * @param scheduleToCloseTimeout the schedule to close timeout for the Nexus operation
     * @return this
     */
    public NexusOperationOptions.Builder setScheduleToCloseTimeout(
        Duration scheduleToCloseTimeout) {
      this.scheduleToCloseTimeout = scheduleToCloseTimeout;
      return this;
    }

    private Builder() {}

    private Builder(NexusOperationOptions options) {
      if (options == null) {
        return;
      }
      this.scheduleToCloseTimeout = options.getScheduleToCloseTimeout();
    }

    public NexusOperationOptions build() {
      return new NexusOperationOptions(scheduleToCloseTimeout);
    }

    public NexusOperationOptions.Builder mergeNexusOperationOptions(
        NexusOperationOptions override) {
      if (override == null) {
        return this;
      }
      this.scheduleToCloseTimeout =
          (override.scheduleToCloseTimeout == null)
              ? this.scheduleToCloseTimeout
              : override.scheduleToCloseTimeout;
      return this;
    }
  }

  private NexusOperationOptions(Duration startToCloseTimeout) {
    this.scheduleToCloseTimeout = startToCloseTimeout;
  }

  private Duration scheduleToCloseTimeout;

  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    NexusOperationOptions that = (NexusOperationOptions) o;
    return Objects.equals(scheduleToCloseTimeout, that.scheduleToCloseTimeout);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scheduleToCloseTimeout);
  }

  @Override
  public String toString() {
    return "NexusOperationOptions{" + "scheduleToCloseTimeout=" + scheduleToCloseTimeout + '}';
  }
}
