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

package io.temporal.common;

import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.internal.common.OptionsUtils;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public final class RetryOptions {

  private static final double DEFAULT_BACKOFF_COEFFICIENT = 2.0;
  private static final int DEFAULT_MAXIMUM_MULTIPLIER = 100;

  public static Builder newBuilder() {
    return new Builder(null);
  }

  /**
   * Creates builder with fields pre-populated from passed options.
   *
   * @param options can be null
   */
  public static Builder newBuilder(RetryOptions options) {
    return new Builder(options);
  }

  public static RetryOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final RetryOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = RetryOptions.newBuilder().build();
  }

  /**
   * Merges annotation with explicitly provided RetryOptions. If there is conflict RetryOptions
   * takes precedence.
   */
  public static RetryOptions merge(MethodRetry r, RetryOptions o) {
    if (r == null) {
      if (o == null) {
        return null;
      }
      return o;
    }
    if (o == null) {
      o = RetryOptions.getDefaultInstance();
    }
    Duration initial = OptionsUtils.merge(r.initialIntervalSeconds(), o.getInitialInterval());
    RetryOptions.Builder builder = RetryOptions.newBuilder();
    if (initial != null) {
      builder.setInitialInterval(initial);
    }
    Duration maximum = OptionsUtils.merge(r.maximumIntervalSeconds(), o.getMaximumInterval());
    if (maximum != null) {
      builder.setMaximumInterval(maximum);
    }
    double coefficient =
        OptionsUtils.merge(r.backoffCoefficient(), o.getBackoffCoefficient(), double.class);
    if (coefficient != 0d) {
      builder.setBackoffCoefficient(coefficient);
    } else {
      builder.setBackoffCoefficient(DEFAULT_BACKOFF_COEFFICIENT);
    }
    return builder
        .setMaximumAttempts(
            OptionsUtils.merge(r.maximumAttempts(), o.getMaximumAttempts(), int.class))
        .setDoNotRetry(OptionsUtils.merge(r.doNotRetry(), o.getDoNotRetry()))
        .build();
  }

  /** The parameter options takes precedence. */
  public RetryOptions merge(RetryOptions o) {
    if (o == null) {
      return this;
    }
    return RetryOptions.newBuilder()
        .setInitialInterval(
            OptionsUtils.merge(getInitialInterval(), o.getInitialInterval(), Duration.class))
        .setMaximumInterval(
            OptionsUtils.merge(getMaximumInterval(), o.getMaximumInterval(), Duration.class))
        .setBackoffCoefficient(
            OptionsUtils.merge(getBackoffCoefficient(), o.getBackoffCoefficient(), double.class))
        .setMaximumAttempts(
            OptionsUtils.merge(getMaximumAttempts(), o.getMaximumAttempts(), int.class))
        .setDoNotRetry(OptionsUtils.merge(getDoNotRetry(), o.getDoNotRetry()))
        .build();
  }

  private void validate() {
    if (initialInterval == null || initialInterval.isNegative()) {
      throw new IllegalStateException(
          "initialInterval has to be a positive value, an actual value " + initialInterval);
    }
    if (maximumInterval != null && maximumInterval.compareTo(initialInterval) < 0) {
      throw new IllegalStateException(
          "maximumInterval cannot be less than initialInterval if set. "
              + "maximumInterval is "
              + maximumInterval
              + " while initialInterval is "
              + initialInterval);
    }
  }

  public static final class Builder {

    private static final Duration DEFAULT_INITIAL_INTERVAL = Duration.ofSeconds(1);

    private Duration initialInterval;

    private double backoffCoefficient;

    private int maximumAttempts;

    private Duration maximumInterval;

    private String[] doNotRetry;

    private Builder(RetryOptions options) {
      if (options == null) {
        return;
      }
      this.backoffCoefficient = options.getBackoffCoefficient();
      this.maximumAttempts = options.getMaximumAttempts();
      this.initialInterval = options.getInitialInterval();
      this.maximumInterval = options.getMaximumInterval();
      this.doNotRetry = options.getDoNotRetry();
    }

    /**
     * Interval of the first retry. If coefficient is 1.0 then it is used for all retries. Required.
     */
    public Builder setInitialInterval(Duration initialInterval) {
      Objects.requireNonNull(initialInterval);
      if (initialInterval.isNegative() || initialInterval.isZero()) {
        throw new IllegalArgumentException("Invalid initial interval: " + initialInterval);
      }
      this.initialInterval = initialInterval;
      return this;
    }

    /**
     * Coefficient used to calculate the next retry interval. The next retry interval is previous
     * interval multiplied by this coefficient. Must be 1 or larger. Default is 2.0.
     */
    public Builder setBackoffCoefficient(double backoffCoefficient) {
      if (backoffCoefficient < 1d) {
        throw new IllegalArgumentException("coefficient less than 1.0: " + backoffCoefficient);
      }
      this.backoffCoefficient = backoffCoefficient;
      return this;
    }

    /**
     * When exceeded the amount of attempts, stop. Even if expiration time is not reached. <br>
     * Default is unlimited.
     *
     * @param maximumAttempts Maximum number of attempts. Default will be used if set to {@code 0}.
     */
    public Builder setMaximumAttempts(int maximumAttempts) {
      if (maximumAttempts < 0) {
        throw new IllegalArgumentException("Invalid maximumAttempts: " + maximumAttempts);
      }
      this.maximumAttempts = maximumAttempts;
      return this;
    }

    /**
     * Maximum interval between retries. Exponential backoff leads to interval increase. This value
     * is the cap of the increase. <br>
     * Default is 100x of initial interval. Can't be less than {@link #setInitialInterval(Duration)}
     *
     * @param maximumInterval the maximum interval value. Default will be used if set to {@code
     *     null}.
     */
    public Builder setMaximumInterval(Duration maximumInterval) {
      if (maximumInterval != null && (maximumInterval.isNegative() || maximumInterval.isZero())) {
        throw new IllegalArgumentException("Invalid maximum interval: " + maximumInterval);
      }
      this.maximumInterval = maximumInterval;
      return this;
    }

    /**
     * List of application failures types to not retry.
     *
     * <p>An application failure is represented as {@link ApplicationFailure}. The type of the
     * failure is stored in the {@link ApplicationFailure#getType()} property. An {@link
     * ApplicationFailure} instance is created through {@link ApplicationFailure#newFailure(String,
     * String, Object...)}. An ApplicationFailure created through {@link
     * ApplicationFailure#newNonRetryableFailure(String, String, Object...)} will have {@link
     * ApplicationFailure#isNonRetryable()} property set to true which means that it is not retried
     * even if the type of failure is not included into DoNotRetry list.
     *
     * <p>If an activity or workflow throws any exception which is not {@link ApplicationFailure}
     * then the exception is converted to {@link ApplicationFailure} with the fully qualified name
     * of the exception as type value.
     *
     * <p>Note that activity failures are wrapped into {@link ActivityFailure} exception. So if an
     * activity failure is not handled the workflow retry policy is going to see ActivityFailure
     * which is always retryable, not the original exception. The same way a child workflow failure
     * is wrapped into {@link ChildWorkflowFailure}.
     *
     * <p>{@link Error} and {@link CanceledFailure} are never retried and are not even passed to
     * this filter.
     */
    public Builder setDoNotRetry(String... doNotRetry) {
      if (doNotRetry != null) {
        this.doNotRetry = doNotRetry;
      }
      return this;
    }

    /**
     * Build RetryOptions without performing validation as validation should be done after merging
     * with {@link MethodRetry}.
     */
    public RetryOptions build() {
      return new RetryOptions(
          initialInterval, backoffCoefficient, maximumAttempts, maximumInterval, doNotRetry);
    }

    /** Validates property values and builds RetryOptions with default values. */
    public RetryOptions validateBuildWithDefaults() {
      double backoff = backoffCoefficient;
      if (backoff == 0d) {
        backoff = DEFAULT_BACKOFF_COEFFICIENT;
      }
      RetryOptions options =
          new RetryOptions(
              initialInterval == null || initialInterval.isZero()
                  ? DEFAULT_INITIAL_INTERVAL
                  : initialInterval,
              backoff,
              maximumAttempts,
              maximumInterval,
              doNotRetry == null ? new String[0] : doNotRetry);
      options.validate();
      return options;
    }
  }

  private final Duration initialInterval;

  private final double backoffCoefficient;

  private final int maximumAttempts;

  private final Duration maximumInterval;

  private final String[] doNotRetry;

  private RetryOptions(
      Duration initialInterval,
      double backoffCoefficient,
      int maximumAttempts,
      Duration maximumInterval,
      String[] doNotRetry) {
    this.initialInterval = initialInterval;
    this.backoffCoefficient = backoffCoefficient;
    this.maximumAttempts = maximumAttempts;
    this.maximumInterval = maximumInterval;
    this.doNotRetry = doNotRetry;
  }

  public Duration getInitialInterval() {
    return initialInterval;
  }

  public double getBackoffCoefficient() {
    return backoffCoefficient;
  }

  public int getMaximumAttempts() {
    return maximumAttempts;
  }

  public Duration getMaximumInterval() {
    return maximumInterval;
  }

  /**
   * @return null if not configured. When merging with annotation it makes a difference. null means
   *     use values from an annotation. Empty list means do not retry on anything.
   */
  public String[] getDoNotRetry() {
    return doNotRetry;
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public String toString() {
    return "RetryOptions{"
        + "initialInterval="
        + initialInterval
        + ", backoffCoefficient="
        + backoffCoefficient
        + ", maximumAttempts="
        + maximumAttempts
        + ", maximumInterval="
        + maximumInterval
        + ", doNotRetry="
        + Arrays.toString(doNotRetry)
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    RetryOptions that = (RetryOptions) o;
    return Double.compare(that.backoffCoefficient, backoffCoefficient) == 0
        && maximumAttempts == that.maximumAttempts
        && Objects.equals(initialInterval, that.initialInterval)
        && Objects.equals(maximumInterval, that.maximumInterval)
        && Arrays.equals(doNotRetry, that.doNotRetry);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        initialInterval,
        backoffCoefficient,
        maximumAttempts,
        maximumInterval,
        Arrays.hashCode(doNotRetry));
  }

  public long calculateSleepTime(long attempt) {
    double coefficient =
        backoffCoefficient == 0d ? DEFAULT_BACKOFF_COEFFICIENT : backoffCoefficient;
    double sleepMillis = Math.pow(coefficient, attempt - 1) * initialInterval.toMillis();
    if (maximumInterval == null) {
      return (long) Math.min(sleepMillis, initialInterval.toMillis() * DEFAULT_MAXIMUM_MULTIPLIER);
    }
    return Math.min((long) sleepMillis, maximumInterval.toMillis());
  }

  public boolean shouldRethrow(
      Throwable e, Optional<Duration> expiration, long attempt, long elapsed, long sleepTime) {
    String type;
    if (e instanceof ActivityFailure || e instanceof ChildWorkflowFailure) {
      e = e.getCause();
    }
    if (e instanceof ApplicationFailure) {
      type = ((ApplicationFailure) e).getType();
    } else {
      type = e.getClass().getName();
    }
    if (doNotRetry != null) {
      for (String doNotRetry : doNotRetry) {
        if (doNotRetry.equals(type)) {
          return true;
        }
      }
    }
    // Attempt that failed.
    if (maximumAttempts != 0 && attempt >= maximumAttempts) {
      return true;
    }
    return expiration.isPresent() && elapsed + sleepTime >= expiration.get().toMillis();
  }
}
