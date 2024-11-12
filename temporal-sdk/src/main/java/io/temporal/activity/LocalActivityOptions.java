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

package io.temporal.activity;

import com.google.common.base.Objects;
import io.temporal.common.Experimental;
import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/** Options used to configure how a local Activity is invoked. */
public final class LocalActivityOptions {

  public static Builder newBuilder() {
    return new Builder(null);
  }

  /**
   * @param o null is allowed.
   */
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
    private Duration scheduleToStartTimeout;
    private Duration localRetryThreshold;
    private RetryOptions retryOptions;
    private Boolean doNotIncludeArgumentsIntoMarker;
    private String summary;

    /** Copy Builder fields from the options. */
    private Builder(LocalActivityOptions options) {
      if (options == null) {
        return;
      }
      this.scheduleToCloseTimeout = options.getScheduleToCloseTimeout();
      this.startToCloseTimeout = options.getStartToCloseTimeout();
      this.scheduleToStartTimeout = options.getScheduleToStartTimeout();
      this.localRetryThreshold = options.getLocalRetryThreshold();
      this.retryOptions = options.getRetryOptions();
      this.doNotIncludeArgumentsIntoMarker = options.isDoNotIncludeArgumentsIntoMarker();
      this.summary = options.getSummary();
    }

    /**
     * Total time that a workflow is willing to wait for an Activity to complete.
     *
     * <p>ScheduleToCloseTimeout limits the total time of an Activity's execution including retries
     * (use {@link #setStartToCloseTimeout(Duration)} to limit the time of a single attempt).
     *
     * <p>Either this option or {@link #setStartToCloseTimeout(Duration)} is required.
     *
     * <p>Defaults to unlimited.
     */
    public Builder setScheduleToCloseTimeout(Duration timeout) {
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Illegal timeout: " + timeout);
      }
      this.scheduleToCloseTimeout = timeout;
      return this;
    }

    /**
     * Time that the Activity Task can stay in the Worker's internal Task Queue of Local Activities
     * until it's picked up by the Local Activity Executor.
     *
     * <p>ScheduleToStartTimeout is always non-retryable. Retrying after this timeout doesn't make
     * sense as it would just put the Activity Task back into the same Task Queue.
     *
     * <p>Defaults to unlimited.
     */
    public Builder setScheduleToStartTimeout(Duration timeout) {
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Illegal timeout: " + timeout);
      }
      this.scheduleToStartTimeout = timeout;
      return this;
    }

    /**
     * Maximum time of a single Activity attempt.
     *
     * <p>If {@link #setScheduleToCloseTimeout(Duration)} is not provided, then this timeout is
     * required.
     */
    public Builder setStartToCloseTimeout(Duration timeout) {
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("Illegal timeout: " + timeout);
      }
      this.startToCloseTimeout = timeout;
      return this;
    }

    /**
     * Maximum time to wait between retries locally, while keeping the Workflow Task open via a
     * Heartbeat. If the delay between the attempts becomes larger that this threshold, a Workflow
     * Timer will be scheduled. Default value is Workflow Task Timeout multiplied by 3.
     */
    public Builder setLocalRetryThreshold(Duration localRetryThreshold) {
      if (localRetryThreshold.isZero() || localRetryThreshold.isNegative()) {
        throw new IllegalArgumentException("Illegal threshold: " + localRetryThreshold);
      }
      this.localRetryThreshold = localRetryThreshold;
      return this;
    }

    /**
     * {@link RetryOptions} that define how an Activity is retried in case of failure.
     *
     * <p>If not provided, the default activity retry policy is:
     *
     * <pre><code>
     *   InitialInterval:         1 second
     *   BackoffCoefficient:      2
     *   MaximumInterval:         100 seconds   // 100 * InitialInterval
     *   MaximumAttempts:         0             // Unlimited
     *   NonRetryableErrorTypes:  []
     * </pre></code>
     *
     * <p>If both {@link #setScheduleToCloseTimeout(Duration)} and {@link
     * RetryOptions.Builder#setMaximumAttempts(int)} are not set, the Activity will not be retried.
     *
     * <p>To ensure zero retries, set {@link RetryOptions.Builder#setMaximumAttempts(int)} to 1.
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

    /**
     * Single-line fixed summary for this activity that will appear in UI/CLI. This can be in
     * single-line Temporal Markdown format.
     *
     * <p>Default is none/empty.
     */
    @Experimental
    public Builder setSummary(String summary) {
      this.summary = summary;
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
      this.startToCloseTimeout =
          (override.startToCloseTimeout == null)
              ? this.startToCloseTimeout
              : override.startToCloseTimeout;
      this.scheduleToStartTimeout =
          (override.scheduleToStartTimeout == null)
              ? this.scheduleToStartTimeout
              : override.scheduleToStartTimeout;
      this.localRetryThreshold =
          (override.localRetryThreshold == null)
              ? this.localRetryThreshold
              : override.localRetryThreshold;
      this.retryOptions =
          (override.retryOptions == null) ? this.retryOptions : override.retryOptions;
      this.doNotIncludeArgumentsIntoMarker =
          (override.doNotIncludeArgumentsIntoMarker != null)
              ? override.doNotIncludeArgumentsIntoMarker
              : this.doNotIncludeArgumentsIntoMarker;
      this.summary = (override.summary == null) ? this.summary : override.summary;
      return this;
    }

    public LocalActivityOptions build() {
      return new LocalActivityOptions(
          startToCloseTimeout,
          scheduleToCloseTimeout,
          scheduleToStartTimeout,
          localRetryThreshold,
          retryOptions,
          doNotIncludeArgumentsIntoMarker,
          summary);
    }

    public LocalActivityOptions validateAndBuildWithDefaults() {
      if (startToCloseTimeout == null && scheduleToCloseTimeout == null) {
        throw new IllegalArgumentException(
            "one of the startToCloseTimeout or scheduleToCloseTimeout is required");
      }
      return new LocalActivityOptions(
          startToCloseTimeout,
          scheduleToCloseTimeout,
          scheduleToStartTimeout,
          localRetryThreshold,
          RetryOptions.newBuilder(retryOptions).validateBuildWithDefaults(),
          doNotIncludeArgumentsIntoMarker,
          summary);
    }
  }

  private final Duration scheduleToCloseTimeout;
  private final Duration localRetryThreshold;
  private final Duration startToCloseTimeout;
  private final Duration scheduleToStartTimeout;
  private final RetryOptions retryOptions;
  private final Boolean doNotIncludeArgumentsIntoMarker;
  private final String summary;

  private LocalActivityOptions(
      Duration startToCloseTimeout,
      Duration scheduleToCloseTimeout,
      Duration scheduleToStartTimeout,
      Duration localRetryThreshold,
      RetryOptions retryOptions,
      Boolean doNotIncludeArgumentsIntoMarker,
      String summary) {
    this.scheduleToCloseTimeout = scheduleToCloseTimeout;
    this.startToCloseTimeout = startToCloseTimeout;
    this.scheduleToStartTimeout = scheduleToStartTimeout;
    this.localRetryThreshold = localRetryThreshold;
    this.retryOptions = retryOptions;
    this.doNotIncludeArgumentsIntoMarker = doNotIncludeArgumentsIntoMarker;
    this.summary = summary;
  }

  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
  }

  public Duration getStartToCloseTimeout() {
    return startToCloseTimeout;
  }

  public Duration getScheduleToStartTimeout() {
    return scheduleToStartTimeout;
  }

  public Duration getLocalRetryThreshold() {
    return localRetryThreshold;
  }

  public RetryOptions getRetryOptions() {
    return retryOptions;
  }

  public boolean isDoNotIncludeArgumentsIntoMarker() {
    return doNotIncludeArgumentsIntoMarker != null && doNotIncludeArgumentsIntoMarker;
  }

  @Experimental
  public String getSummary() {
    return summary;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof LocalActivityOptions)) return false;
    LocalActivityOptions that = (LocalActivityOptions) o;
    return Objects.equal(doNotIncludeArgumentsIntoMarker, that.doNotIncludeArgumentsIntoMarker)
        && Objects.equal(scheduleToCloseTimeout, that.scheduleToCloseTimeout)
        && Objects.equal(startToCloseTimeout, that.startToCloseTimeout)
        && Objects.equal(scheduleToStartTimeout, that.scheduleToStartTimeout)
        && Objects.equal(localRetryThreshold, that.localRetryThreshold)
        && Objects.equal(retryOptions, that.retryOptions)
        && Objects.equal(summary, that.summary);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        scheduleToCloseTimeout,
        startToCloseTimeout,
        scheduleToStartTimeout,
        localRetryThreshold,
        retryOptions,
        doNotIncludeArgumentsIntoMarker,
        summary);
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
        + ", summary="
        + summary
        + '}';
  }
}
