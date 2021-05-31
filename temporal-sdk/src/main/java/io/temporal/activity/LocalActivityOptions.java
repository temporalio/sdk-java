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

/** Options used to configure how a local Activity is invoked. */
public final class LocalActivityOptions {

  public static Builder newBuilder() {
    return new Builder(null);
  }

  /** @param o null is allowed. */
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
    private Duration localRetryThreshold;
    private Duration startToCloseTimeout;
    private RetryOptions retryOptions;
    private Boolean doNotIncludeArgumentsIntoMarker;

    /** Copy Builder fields from the options. */
    private Builder(LocalActivityOptions options) {
      if (options == null) {
        return;
      }
      this.scheduleToCloseTimeout = options.getScheduleToCloseTimeout();
      this.localRetryThreshold = options.getLocalRetryThreshold();
      this.startToCloseTimeout = options.getStartToCloseTimeout();
      this.retryOptions = options.getRetryOptions();
      this.doNotIncludeArgumentsIntoMarker = options.isDoNotIncludeArgumentsIntoMarker();
    }

    /**
     * Overall time a Workflow is willing to wait for an Activity's completion. This includes all
     * retries.
     */
    public Builder setScheduleToCloseTimeout(Duration timeout) {
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Illegal timeout: " + timeout);
      }
      this.scheduleToCloseTimeout = timeout;
      return this;
    }

    /**
     * Maximum time to retry locally, while keeping the Workflow Task open via a Heartbeat. Default
     * value is Workflow Task timeout multiplied by 6.
     */
    public Builder setLocalRetryThreshold(Duration localRetryThreshold) {
      if (localRetryThreshold.isZero() || localRetryThreshold.isNegative()) {
        throw new IllegalArgumentException("Illegal threshold: " + localRetryThreshold);
      }
      this.localRetryThreshold = localRetryThreshold;
      return this;
    }

    public Builder setStartToCloseTimeout(Duration timeout) {
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Illegal timeout: " + timeout);
      }
      this.startToCloseTimeout = timeout;
      return this;
    }

    public Builder mergeActivityOptions(LocalActivityOptions override) {
      if (override == null) {
        return this;
      }
      this.scheduleToCloseTimeout =
          (override.scheduleToCloseTimeout == null)
              ? this.scheduleToCloseTimeout
              : override.scheduleToCloseTimeout;
      this.localRetryThreshold =
          (override.localRetryThreshold == null)
              ? this.localRetryThreshold
              : override.localRetryThreshold;
      this.startToCloseTimeout =
          (override.startToCloseTimeout == null)
              ? this.startToCloseTimeout
              : override.startToCloseTimeout;
      this.retryOptions =
          (override.retryOptions == null) ? this.retryOptions : override.retryOptions;
      this.doNotIncludeArgumentsIntoMarker =
          (override.doNotIncludeArgumentsIntoMarker != null)
              ? override.doNotIncludeArgumentsIntoMarker
              : this.doNotIncludeArgumentsIntoMarker;
      return this;
    }

    /**
     * {@link RetryOptions} that define how an Activity is retried in case of failure. Activities
     * use a default RetryPolicy if not provided.
     */
    public Builder setRetryOptions(RetryOptions retryOptions) {
      this.retryOptions = retryOptions;
      return this;
    }

    /**
     * Merges {@link MethodRetry} annotation. The values of this builder take precedence over
     * annotated ones.
     */
    public Builder setMethodRetry(MethodRetry r) {
      if (r != null) {
        this.retryOptions = RetryOptions.merge(r, retryOptions);
      }
      return this;
    }

    /**
     * When set to true, the serialized arguments of the local Activity are not included in the
     * Marker Event that stores the local Activity's invocation result. The serialized arguments are
     * included only for human troubleshooting as they are never read by the SDK code. In some
     * cases, it is better to not include them to reduce the history size. The default value is set
     * to false.
     */
    public Builder setDoNotIncludeArgumentsIntoMarker(boolean doNotIncludeArgumentsIntoMarker) {
      this.doNotIncludeArgumentsIntoMarker = doNotIncludeArgumentsIntoMarker;
      return this;
    }

    public LocalActivityOptions build() {
      return new LocalActivityOptions(
          startToCloseTimeout,
          localRetryThreshold,
          scheduleToCloseTimeout,
          retryOptions,
          doNotIncludeArgumentsIntoMarker);
    }

    public LocalActivityOptions validateAndBuildWithDefaults() {
      if (startToCloseTimeout == null && scheduleToCloseTimeout == null) {
        throw new IllegalArgumentException(
            "one of the startToCloseTimeout or scheduleToCloseTimeout is required");
      }
      return new LocalActivityOptions(
          startToCloseTimeout,
          localRetryThreshold,
          scheduleToCloseTimeout,
          RetryOptions.newBuilder(retryOptions).validateBuildWithDefaults(),
          doNotIncludeArgumentsIntoMarker);
    }
  }

  private final Duration scheduleToCloseTimeout;
  private final Duration localRetryThreshold;
  private final Duration startToCloseTimeout;
  private final RetryOptions retryOptions;
  private Boolean doNotIncludeArgumentsIntoMarker;

  private LocalActivityOptions(
      Duration startToCloseTimeout,
      Duration localRetryThreshold,
      Duration scheduleToCloseTimeout,
      RetryOptions retryOptions,
      Boolean doNotIncludeArgumentsIntoMarker) {
    this.localRetryThreshold = localRetryThreshold;
    this.scheduleToCloseTimeout = scheduleToCloseTimeout;
    this.startToCloseTimeout = startToCloseTimeout;
    this.retryOptions = retryOptions;
    this.doNotIncludeArgumentsIntoMarker = doNotIncludeArgumentsIntoMarker;
  }

  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
  }

  public Duration getLocalRetryThreshold() {
    return localRetryThreshold;
  }

  public Duration getStartToCloseTimeout() {
    return startToCloseTimeout;
  }

  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  public boolean isDoNotIncludeArgumentsIntoMarker() {
    return (doNotIncludeArgumentsIntoMarker == null) ? false : doNotIncludeArgumentsIntoMarker;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof LocalActivityOptions)) return false;
    LocalActivityOptions that = (LocalActivityOptions) o;
    return doNotIncludeArgumentsIntoMarker == that.doNotIncludeArgumentsIntoMarker
        && Objects.equal(scheduleToCloseTimeout, that.scheduleToCloseTimeout)
        && Objects.equal(localRetryThreshold, that.localRetryThreshold)
        && Objects.equal(startToCloseTimeout, that.startToCloseTimeout)
        && Objects.equal(retryOptions, that.retryOptions);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        scheduleToCloseTimeout,
        localRetryThreshold,
        startToCloseTimeout,
        retryOptions,
        doNotIncludeArgumentsIntoMarker);
  }

  @Override
  public String toString() {
    return "LocalActivityOptions{"
        + "scheduleToCloseTimeout="
        + scheduleToCloseTimeout
        + ", localRetryThreshold="
        + localRetryThreshold
        + ", startToCloseTimeout="
        + startToCloseTimeout
        + ", retryOptions="
        + retryOptions
        + ", doNotIncludeArgumentsIntoMarker="
        + isDoNotIncludeArgumentsIntoMarker()
        + '}';
  }
}
