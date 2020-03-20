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

import com.google.common.base.Defaults;
import io.temporal.workflow.ActivityFailureException;
import io.temporal.workflow.ChildWorkflowFailureException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RetryOptions {

  private static final double DEFAULT_BACKOFF_COEFFICIENT = 2.0;
  private static final int DEFAULT_MAXIMUM_MULTIPLIER = 100;

  public static Builder newBuilder() {
    return new Builder();
  }

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
      return RetryOptions.newBuilder(o).validateBuildWithDefaults();
    }
    if (o == null) {
      o = RetryOptions.newBuilder().build();
    }
    Duration initial = merge(r.initialIntervalSeconds(), o.getInitialInterval());
    RetryOptions.Builder builder = RetryOptions.newBuilder();
    if (initial != null) {
      builder.setInitialInterval(initial);
    }
    Duration expiration = merge(r.expirationSeconds(), o.getExpiration());
    if (expiration != null) {
      builder.setExpiration(expiration);
    }
    Duration maximum = merge(r.maximumIntervalSeconds(), o.getMaximumInterval());
    if (maximum != null) {
      builder.setMaximumInterval(maximum);
    }
    double coefficient = merge(r.backoffCoefficient(), o.getBackoffCoefficient(), double.class);
    if (coefficient != 0d) {
      builder.setBackoffCoefficient(coefficient);
    } else {
      builder.setBackoffCoefficient(DEFAULT_BACKOFF_COEFFICIENT);
    }
    return builder
        .setMaximumAttempts(merge(r.maximumAttempts(), o.getMaximumAttempts(), int.class))
        .setDoNotRetry(merge(r.doNotRetry(), o.getDoNotRetry()))
        .validateBuildWithDefaults();
  }

  /** The parameter options takes precedence. */
  public RetryOptions merge(RetryOptions o) {
    if (o == null) {
      return this;
    }
    return RetryOptions.newBuilder()
        .setInitialInterval(merge(getInitialInterval(), o.getInitialInterval(), Duration.class))
        .setExpiration(merge(getExpiration(), o.getExpiration(), Duration.class))
        .setMaximumInterval(merge(getMaximumInterval(), o.getMaximumInterval(), Duration.class))
        .setBackoffCoefficient(
            merge(getBackoffCoefficient(), o.getBackoffCoefficient(), double.class))
        .setMaximumAttempts(merge(getMaximumAttempts(), o.getMaximumAttempts(), int.class))
        .setDoNotRetry(merge(getDoNotRetry(), o.getDoNotRetry()))
        .validateBuildWithDefaults();
  }

  @SafeVarargs
  public final RetryOptions addDoNotRetry(Class<? extends Throwable>... doNotRetry) {
    if (doNotRetry == null) {
      return this;
    }

    double backoffCoefficient = getBackoffCoefficient();
    if (backoffCoefficient == 0) {
      backoffCoefficient = DEFAULT_BACKOFF_COEFFICIENT;
    }

    RetryOptions.Builder builder =
        RetryOptions.newBuilder()
            .setInitialInterval(getInitialInterval())
            .setExpiration(getExpiration())
            .setMaximumInterval(getMaximumInterval())
            .setBackoffCoefficient(backoffCoefficient)
            .setDoNotRetry(merge(getDoNotRetry(), Arrays.asList(doNotRetry)));

    if (getMaximumAttempts() > 0) {
      builder.setMaximumAttempts(getMaximumAttempts());
    }
    return builder.validateBuildWithDefaults();
  }

  public static final class Builder {

    private Duration initialInterval;

    private Duration expiration;

    private double backoffCoefficient;

    private int maximumAttempts;

    private Duration maximumInterval;

    private List<Class<? extends Throwable>> doNotRetry;

    private Builder() {}

    private Builder(RetryOptions options) {
      if (options == null) {
        return;
      }
      this.backoffCoefficient = options.getBackoffCoefficient();
      this.maximumAttempts = options.getMaximumAttempts();
      this.expiration = options.getExpiration();
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
        throw new IllegalArgumentException("Invalid interval: " + initialInterval);
      }
      this.initialInterval = initialInterval;
      return this;
    }

    /**
     * Maximum time to retry. Required. When exceeded the retries stop even if maximum retries is
     * not reached yet.
     */
    public Builder setExpiration(Duration expiration) {
      Objects.requireNonNull(expiration);
      if (expiration.isNegative() || expiration.isZero()) {
        throw new IllegalArgumentException("Invalid interval: " + expiration);
      }
      this.expiration = expiration;
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
     * Maximum number of attempts. When exceeded the retries stop even if not expired yet. Must be 1
     * or bigger. Default is unlimited.
     */
    public Builder setMaximumAttempts(int maximumAttempts) {
      if (maximumAttempts < 1) {
        throw new IllegalArgumentException("Invalid maximumAttempts: " + maximumAttempts);
      }
      this.maximumAttempts = maximumAttempts;
      return this;
    }

    /**
     * Maximum interval between retries. Exponential backoff leads to interval increase. This value
     * is the cap of the increase. Default is 100x of initial interval.
     */
    public Builder setMaximumInterval(Duration maximumInterval) {
      Objects.requireNonNull(maximumInterval);
      if (maximumInterval.isNegative() || maximumInterval.isZero()) {
        throw new IllegalArgumentException("Invalid interval: " + maximumInterval);
      }
      this.maximumInterval = maximumInterval;
      return this;
    }

    /**
     * List of exceptions to retry. When matching an exact match is used. So adding
     * RuntimeException.class to this list is going to include only RuntimeException itself, not all
     * of its subclasses. The reason for such behaviour is to be able to support server side retries
     * without knowledge of Java exception hierarchy. When considering an exception type a cause of
     * {@link io.temporal.workflow.ActivityFailureException} and {@link
     * io.temporal.workflow.ChildWorkflowFailureException} is looked at.
     *
     * <p>{@link Error} and {@link java.util.concurrent.CancellationException} are never retried and
     * are not even passed to this filter.
     */
    @SafeVarargs
    public final Builder setDoNotRetry(Class<? extends Throwable>... doNotRetry) {
      if (doNotRetry != null) {
        this.doNotRetry = Arrays.asList(doNotRetry);
      }
      return this;
    }

    /**
     * Build RetryOptions without performing validation as validation should be done after merging
     * with {@link MethodRetry}.
     */
    public RetryOptions build() {
      return new RetryOptions(
          initialInterval,
          backoffCoefficient,
          expiration,
          maximumAttempts,
          maximumInterval,
          doNotRetry);
    }

    /** Validates property values and builds RetryOptions with default values. */
    public RetryOptions validateBuildWithDefaults() {
      double backoff = backoffCoefficient;
      if (backoff == 0d) {
        backoff = DEFAULT_BACKOFF_COEFFICIENT;
      }
      RetryOptions result =
          new RetryOptions(
              initialInterval, backoff, expiration, maximumAttempts, maximumInterval, doNotRetry);
      result.validate();
      return result;
    }
  }

  private final Duration initialInterval;

  private final double backoffCoefficient;

  private final Duration expiration;

  private final int maximumAttempts;

  private final Duration maximumInterval;

  private final List<Class<? extends Throwable>> doNotRetry;

  private RetryOptions(
      Duration initialInterval,
      double backoffCoefficient,
      Duration expiration,
      int maximumAttempts,
      Duration maximumInterval,
      List<Class<? extends Throwable>> doNotRetry) {
    this.initialInterval = initialInterval;
    this.backoffCoefficient = backoffCoefficient;
    this.expiration = expiration;
    this.maximumAttempts = maximumAttempts;
    this.maximumInterval = maximumInterval;
    this.doNotRetry = doNotRetry != null ? Collections.unmodifiableList(doNotRetry) : null;
  }

  public Duration getInitialInterval() {
    return initialInterval;
  }

  public double getBackoffCoefficient() {
    return backoffCoefficient;
  }

  public Duration getExpiration() {
    return expiration;
  }

  public int getMaximumAttempts() {
    return maximumAttempts;
  }

  public Duration getMaximumInterval() {
    return maximumInterval;
  }

  public void validate() {
    if (initialInterval == null) {
      throw new IllegalStateException("required property initialInterval not set");
    }
    if (expiration == null && maximumAttempts <= 0) {
      throw new IllegalArgumentException(
          "both MaximumAttempts and Expiration on retry policy are not set, at least one of them must be set");
    }
    if (maximumInterval != null && maximumInterval.compareTo(initialInterval) < 0) {
      throw new IllegalStateException(
          "maximumInterval("
              + maximumInterval
              + ") cannot be smaller than initialInterval("
              + initialInterval);
    }
    if (backoffCoefficient != 0d && backoffCoefficient < 1.0) {
      throw new IllegalArgumentException("coefficient less than 1");
    }
    if (maximumAttempts != 0 && maximumAttempts < 0) {
      throw new IllegalArgumentException("negative maximum attempts");
    }
  }

  /**
   * @return null if not configured. When merging with annotation it makes a difference. null means
   *     use values from an annotation. Empty list means do not retry on anything.
   */
  public List<Class<? extends Throwable>> getDoNotRetry() {
    return doNotRetry;
  }

  @Override
  public String toString() {
    return "RetryOptions{"
        + "initialInterval="
        + initialInterval
        + ", backoffCoefficient="
        + backoffCoefficient
        + ", expiration="
        + expiration
        + ", maximumAttempts="
        + maximumAttempts
        + ", maximumInterval="
        + maximumInterval
        + ", doNotRetry="
        + doNotRetry
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
        && Objects.equals(expiration, that.expiration)
        && Objects.equals(maximumInterval, that.maximumInterval)
        && Objects.equals(doNotRetry, that.doNotRetry);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        initialInterval,
        backoffCoefficient,
        expiration,
        maximumAttempts,
        maximumInterval,
        doNotRetry);
  }

  private static <G> G merge(G annotation, G options, Class<G> type) {
    if (!Defaults.defaultValue(type).equals(options)) {
      return options;
    }
    return annotation;
  }

  private static Duration merge(long aSeconds, Duration o) {
    if (o != null) {
      return o;
    }
    return aSeconds == 0 ? null : Duration.ofSeconds(aSeconds);
  }

  private static Class<? extends Throwable>[] merge(
      Class<? extends Throwable>[] a, List<Class<? extends Throwable>> o) {
    if (o != null) {
      @SuppressWarnings("unchecked")
      Class<? extends Throwable>[] result = new Class[o.size()];
      return o.toArray(result);
    }
    return a.length == 0 ? null : a;
  }

  private Class<? extends Throwable>[] merge(
      List<Class<? extends Throwable>> o1, List<Class<? extends Throwable>> o2) {
    if (o2 != null) {
      @SuppressWarnings("unchecked")
      Class<? extends Throwable>[] result = new Class[o2.size()];
      return o2.toArray(result);
    }
    if (o1.size() > 0) {
      @SuppressWarnings("unchecked")
      Class<? extends Throwable>[] result = new Class[o1.size()];
      return o1.toArray(result);
    }
    return null;
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

  public boolean shouldRethrow(Throwable e, long attempt, long elapsed, long sleepTime) {
    if (e instanceof ActivityFailureException || e instanceof ChildWorkflowFailureException) {
      e = e.getCause();
    }
    if (doNotRetry != null) {
      for (Class<? extends Throwable> doNotRetry : doNotRetry) {
        if (doNotRetry.equals(e.getClass())) {
          return true;
        }
      }
    }
    // Attempt that failed.
    if (maximumAttempts != 0 && attempt >= maximumAttempts) {
      return true;
    }
    return expiration != null && elapsed + sleepTime >= expiration.toMillis();
  }
}
