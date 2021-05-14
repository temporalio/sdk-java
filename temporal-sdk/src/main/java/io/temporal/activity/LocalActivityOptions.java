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
import io.temporal.common.context.ContextPropagator;
import java.time.Duration;
import java.util.List;

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
    private Duration localRetryThreshold;
    private Duration startToCloseTimeout;
    private RetryOptions retryOptions;
    private List<ContextPropagator> contextPropagators;
    private Boolean doNotIncludeArgumentsIntoMarker;

    /** Copy Builder fields from the options. */
    private Builder(LocalActivityOptions options) {
      if (options == null) {
        return;
      }
      this.scheduleToCloseTimeout = options.scheduleToCloseTimeout;
      this.localRetryThreshold = options.localRetryThreshold;
      this.startToCloseTimeout = options.startToCloseTimeout;
      this.retryOptions = options.retryOptions;
      this.contextPropagators = options.contextPropagators;
      this.doNotIncludeArgumentsIntoMarker = options.doNotIncludeArgumentsIntoMarker;
    }

    /** Overall timeout workflow is willing to wait for activity to complete. */
    public Builder setScheduleToCloseTimeout(Duration timeout) {
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Illegal timeout: " + timeout);
      }
      this.scheduleToCloseTimeout = timeout;
      return this;
    }

    /**
     * Maximum time to retry locally keeping workflow task open through heartbeat. Default is 6
     * workflow task timeout.
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

    /**
     * RetryOptions that define how activity is retried in case of failure. Default is null which is
     * no retries.
     */
    public Builder setRetryOptions(RetryOptions retryOptions) {
      this.retryOptions = retryOptions;
      return this;
    }

    /**
     * ContextPropagators help propagate the context from the workflow to the activities. This
     * setting works like an override for contextPropagators specified in WorkflowOptions. Note that
     * this option applies only to a stub/clint side of the activity call. It means that the
     * Activity Worker still uses ContextPropagators from the Worker's WorkflowClientOptions.
     */
    public Builder setContextPropagators(List<ContextPropagator> contextPropagators) {
      this.contextPropagators = contextPropagators;
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

    /**
     * When set to true the serialized arguments of the local activity are not included into the
     * Marker Event that stores local activity invocation result.
     *
     * <p>The serialized arguments are included only for human troubleshooting as they are never
     * read by the SDK code. So in some cases it is worth not including them to reduce the history
     * size.
     *
     * <p>Default is false.
     */
    public Builder setDoNotIncludeArgumentsIntoMarker(boolean doNotIncludeArgumentsIntoMarker) {
      this.doNotIncludeArgumentsIntoMarker = doNotIncludeArgumentsIntoMarker;
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
      if (this.contextPropagators == null) {
        this.contextPropagators = override.contextPropagators;
      } else if (override.contextPropagators != null) {
        this.contextPropagators.addAll(override.contextPropagators);
      }
      this.doNotIncludeArgumentsIntoMarker =
          (override.doNotIncludeArgumentsIntoMarker != null)
              ? override.doNotIncludeArgumentsIntoMarker
              : this.doNotIncludeArgumentsIntoMarker;
      return this;
    }

    public LocalActivityOptions build() {
      return new LocalActivityOptions(
          startToCloseTimeout,
          localRetryThreshold,
          scheduleToCloseTimeout,
          retryOptions,
          contextPropagators,
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
          contextPropagators,
          doNotIncludeArgumentsIntoMarker);
    }
  }

  private final Duration scheduleToCloseTimeout;
  private final Duration localRetryThreshold;
  private final Duration startToCloseTimeout;
  private final RetryOptions retryOptions;
  private final List<ContextPropagator> contextPropagators;
  private Boolean doNotIncludeArgumentsIntoMarker;

  private LocalActivityOptions(
      Duration startToCloseTimeout,
      Duration localRetryThreshold,
      Duration scheduleToCloseTimeout,
      RetryOptions retryOptions,
      List<ContextPropagator> contextPropagators,
      Boolean doNotIncludeArgumentsIntoMarker) {
    this.localRetryThreshold = localRetryThreshold;
    this.scheduleToCloseTimeout = scheduleToCloseTimeout;
    this.startToCloseTimeout = startToCloseTimeout;
    this.retryOptions = retryOptions;
    this.contextPropagators = contextPropagators;
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

  public List<ContextPropagator> getContextPropagators() {
    return contextPropagators;
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
        && Objects.equal(retryOptions, that.retryOptions)
        && Objects.equal(contextPropagators, that.contextPropagators);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        scheduleToCloseTimeout,
        localRetryThreshold,
        startToCloseTimeout,
        retryOptions,
        contextPropagators,
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
        + ", contextPropagators="
        + contextPropagators
        + ", doNotIncludeArgumentsIntoMarker="
        + isDoNotIncludeArgumentsIntoMarker()
        + '}';
  }
}
