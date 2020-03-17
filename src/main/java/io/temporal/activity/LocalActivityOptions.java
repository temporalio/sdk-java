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

package io.temporal.activity;

import static io.temporal.internal.common.OptionsUtils.roundUpToSeconds;

import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.Objects;

/** Options used to configure how an local activity is invoked. */
public final class LocalActivityOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

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
    private RetryOptions retryOptions;

    public Builder() {}

    /** Copy Builder fields from the options. */
    public Builder(LocalActivityOptions options) {
      if (options == null) {
        return;
      }
      this.scheduleToCloseTimeout = options.getScheduleToCloseTimeout();
      this.retryOptions = options.retryOptions;
    }

    /** Overall timeout workflow is willing to wait for activity to complete. */
    public Builder setScheduleToCloseTimeout(Duration scheduleToCloseTimeout) {
      this.scheduleToCloseTimeout = scheduleToCloseTimeout;
      return this;
    }

    /**
     * RetryOptions that define how activity is retried in case of failure. Default is null which is
     * no reties.
     */
    public Builder setRetryOptions(RetryOptions retryOptions) {
      this.retryOptions = retryOptions;
      return this;
    }

    /**
     * Merges ActivityMethod annotation. The values of this builder take precedence over annotation
     * ones.
     */
    public Builder setActivityMethod(ActivityMethod a) {
      if (a != null) {
        this.scheduleToCloseTimeout =
            ActivityOptions.mergeDuration(
                a.scheduleToCloseTimeoutSeconds(), scheduleToCloseTimeout);
      }
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
      return new LocalActivityOptions(scheduleToCloseTimeout, retryOptions);
    }

    public LocalActivityOptions validateAndBuildWithDefaults() {
      RetryOptions ro = null;
      if (retryOptions != null) {
        ro = RetryOptions.newBuilder(retryOptions).validateBuildWithDefaults();
      }
      return new LocalActivityOptions(roundUpToSeconds(scheduleToCloseTimeout), ro);
    }
  }

  private final Duration scheduleToCloseTimeout;
  private final RetryOptions retryOptions;

  private LocalActivityOptions(Duration scheduleToCloseTimeout, RetryOptions retryOptions) {
    this.scheduleToCloseTimeout = scheduleToCloseTimeout;
    this.retryOptions = retryOptions;
  }

  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
  }

  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  @Override
  public String toString() {
    return "LocalActivityOptions{"
        + "scheduleToCloseTimeout="
        + scheduleToCloseTimeout
        + ", retryOptions="
        + retryOptions
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    LocalActivityOptions that = (LocalActivityOptions) o;
    return Objects.equals(scheduleToCloseTimeout, that.scheduleToCloseTimeout)
        && Objects.equals(retryOptions, that.retryOptions);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scheduleToCloseTimeout, retryOptions);
  }
}
