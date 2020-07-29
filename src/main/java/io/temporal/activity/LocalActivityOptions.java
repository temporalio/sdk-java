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

package io.temporal.activity;

import com.google.common.base.Objects;
import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/** Options used to configure how an local activity is invoked. */
public final class LocalActivityOptions {

  public static Builder newBuilder() {
    return new Builder(null);
  }

  /** @param o null is allowed */
  public static Builder newBuilder(LocalActivityOptions o) {
    return new Builder(o);
  }

  public static LocalActivityOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final LocalActivityOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = LocalActivityOptions.newBuilder().build();
  }

  public static final class Builder {
    private Duration scheduleToCloseTimeout;
    private Duration startToCloseTimeout;
    private RetryOptions retryOptions;

    /** Copy Builder fields from the options. */
    private Builder(LocalActivityOptions options) {
      if (options == null) {
        return;
      }
      this.scheduleToCloseTimeout = options.getScheduleToCloseTimeout();
      this.startToCloseTimeout = options.getStartToCloseTimeout();
      this.retryOptions = options.retryOptions;
    }

    /** Overall timeout workflow is willing to wait for activity to complete. */
    public Builder setScheduleToCloseTimeout(Duration scheduleToCloseTimeout) {
      this.scheduleToCloseTimeout = scheduleToCloseTimeout;
      return this;
    }

    public Builder setStartToCloseTimeout(Duration startToCloseTimeout) {
      this.startToCloseTimeout = startToCloseTimeout;
      return this;
    }

    /**
     * RetryOptions that define how activity is retried in case of failure. Default is null which is
     * no retries.
     */
    public Builder setRetryOptions(RetryOptions retryOptions) {
      this.retryOptions = retryOptions;
      return this;
    }

    /**
     * Merges MethodRetry annotation. The values of this builder take precedence over annotation
     * ones.
     */
    public Builder setMethodRetry(MethodRetry r) {
      if (r != null) {
        this.retryOptions = RetryOptions.merge(r, retryOptions);
      }
      return this;
    }

    public LocalActivityOptions build() {
      return new LocalActivityOptions(scheduleToCloseTimeout, startToCloseTimeout, retryOptions);
    }

    public LocalActivityOptions validateAndBuildWithDefaults() {
      RetryOptions ro = null;
      if (retryOptions != null) {
        ro = RetryOptions.newBuilder(retryOptions).validateBuildWithDefaults();
      }
      return new LocalActivityOptions(scheduleToCloseTimeout, startToCloseTimeout, ro);
    }
  }

  private final Duration scheduleToCloseTimeout;
  private final Duration startToCloseTimeout;
  private final RetryOptions retryOptions;

  private LocalActivityOptions(
      Duration scheduleToCloseTimeout, Duration startToCloseTimeout, RetryOptions retryOptions) {
    this.scheduleToCloseTimeout = scheduleToCloseTimeout;
    this.startToCloseTimeout = startToCloseTimeout;
    this.retryOptions = retryOptions;
  }

  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
  }

  public Duration getStartToCloseTimeout() {
    return startToCloseTimeout;
  }

  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    LocalActivityOptions that = (LocalActivityOptions) o;
    return Objects.equal(scheduleToCloseTimeout, that.scheduleToCloseTimeout)
        && Objects.equal(startToCloseTimeout, that.startToCloseTimeout)
        && Objects.equal(retryOptions, that.retryOptions);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(scheduleToCloseTimeout, startToCloseTimeout, retryOptions);
  }

  @Override
  public String toString() {
    return "LocalActivityOptions{"
        + "scheduleToCloseTimeout="
        + scheduleToCloseTimeout
        + ", startToCloseTimeout="
        + startToCloseTimeout
        + ", retryOptions="
        + retryOptions
        + '}';
  }
}
