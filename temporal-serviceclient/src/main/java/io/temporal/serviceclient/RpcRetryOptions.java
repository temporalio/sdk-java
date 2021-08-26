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

package io.temporal.serviceclient;

import com.google.common.base.Defaults;
import com.google.protobuf.GeneratedMessageV3;
import io.grpc.Status;
import io.temporal.serviceclient.rpcretry.DefaultStubServiceOperationRpcRetryOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class RpcRetryOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(RpcRetryOptions options) {
    return new Builder(options);
  }

  public static RpcRetryOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final RpcRetryOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = RpcRetryOptions.newBuilder().build();
  }

  public static class DoNotRetryItem {
    private final Status.Code code;
    private final Class<? extends GeneratedMessageV3> detailsClass;

    /**
     * @param code errors with this code will be considered non retryable. {@link
     *     Status.Code#CANCELLED} and {@link Status.Code#DEADLINE_EXCEEDED} are always considered
     *     non-retryable.
     * @param detailsClass If not null, only failures with the {@code code} and details of this
     *     {@code detailsClass} class are non retryable If null, all failures with the code are non
     *     retryable.
     */
    public DoNotRetryItem(
        @Nonnull Status.Code code, @Nullable Class<? extends GeneratedMessageV3> detailsClass) {
      if (code == null) {
        throw new NullPointerException("code");
      }
      this.code = code;
      this.detailsClass = detailsClass;
    }

    public Status.Code getCode() {
      return code;
    }

    public Class<? extends GeneratedMessageV3> getDetailsClass() {
      return detailsClass;
    }
  }

  public static final class Builder {

    private Duration initialInterval;

    private Duration expiration;

    private double backoffCoefficient;

    private int maximumAttempts;

    private Duration maximumInterval;

    private List<DoNotRetryItem> doNotRetry = new ArrayList<>();

    private Builder() {}

    private Builder(RpcRetryOptions options) {
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
     * Add <code>Status.Code</code> with associated details class to not retry. If <code>
     * detailsClass</code> is null all failures with the code are non retryable.
     *
     * <p>{@link Status.Code#CANCELLED} and {@link Status.Code#DEADLINE_EXCEEDED} are always
     * considered non-retryable.
     */
    public Builder addDoNotRetry(
        Status.Code code, Class<? extends GeneratedMessageV3> detailsClass) {
      doNotRetry.add(new DoNotRetryItem(code, detailsClass));
      return this;
    }

    /**
     * Add {@link DoNotRetryItem} to not retry. If <code>DoNotRetryItem#detailsClass</code> is null
     * all failures with the code are non retryable.
     *
     * <p>{@link Status.Code#CANCELLED} and {@link Status.Code#DEADLINE_EXCEEDED} are always
     * considered non-retryable.
     */
    public Builder addDoNotRetry(DoNotRetryItem doNotRetryItem) {
      doNotRetry.add(doNotRetryItem);
      return this;
    }

    Builder setDoNotRetry(List<DoNotRetryItem> pairs) {
      doNotRetry = pairs;
      return this;
    }

    /** The parameter options takes precedence. */
    public Builder setRetryOptions(RpcRetryOptions o) {
      if (o == null) {
        return this;
      }
      setInitialInterval(merge(initialInterval, o.getInitialInterval(), Duration.class));
      setExpiration(merge(expiration, o.getExpiration(), Duration.class));
      setMaximumInterval(merge(maximumInterval, o.getMaximumInterval(), Duration.class));
      setBackoffCoefficient(merge(backoffCoefficient, o.getBackoffCoefficient(), double.class));
      setMaximumAttempts(merge(maximumAttempts, o.getMaximumAttempts(), int.class));
      setDoNotRetry(merge(doNotRetry, o.getDoNotRetry()));
      validateBuildWithDefaults();
      return this;
    }

    private static <G> G merge(G annotation, G options, Class<G> type) {
      if (!Defaults.defaultValue(type).equals(options)) {
        return options;
      }
      return annotation;
    }

    private List<DoNotRetryItem> merge(List<DoNotRetryItem> o1, List<DoNotRetryItem> o2) {
      if (o2 != null) {
        return new ArrayList<>(o2);
      }
      if (o1.size() > 0) {
        return new ArrayList<>(o1);
      }
      return null;
    }

    /**
     * Build RetryOptions without performing validation as validation should be done after merging
     * with MethodRetry.
     */
    public RpcRetryOptions build() {
      return new RpcRetryOptions(
          initialInterval,
          backoffCoefficient,
          expiration,
          maximumAttempts,
          maximumInterval,
          doNotRetry);
    }

    public RpcRetryOptions buildWithDefaultsFrom(RpcRetryOptions rpcRetryOptions) {
      return RpcRetryOptions.newBuilder()
          .setRetryOptions(rpcRetryOptions)
          .validateBuildWithDefaults();
    }

    /** Validates property values and builds RetryOptions with default values. */
    public RpcRetryOptions validateBuildWithDefaults() {
      double backoff = backoffCoefficient;
      if (backoff == 0d) {
        backoff = DefaultStubServiceOperationRpcRetryOptions.BACKOFF;
      }
      if (initialInterval == null || initialInterval.isZero() || initialInterval.isNegative()) {
        initialInterval = DefaultStubServiceOperationRpcRetryOptions.INITIAL_INTERVAL;
      }
      if (expiration == null || expiration.isZero() || expiration.isNegative()) {
        expiration = DefaultStubServiceOperationRpcRetryOptions.EXPIRATION_INTERVAL;
      }
      if (maximumInterval == null || maximumInterval.isZero() || maximumInterval.isNegative()) {
        maximumInterval = DefaultStubServiceOperationRpcRetryOptions.MAXIMUM_INTERVAL;
      }
      if (doNotRetry == null || doNotRetry.size() == 0) {
        doNotRetry = DefaultStubServiceOperationRpcRetryOptions.INSTANCE.doNotRetry;
      }
      RpcRetryOptions result =
          new RpcRetryOptions(
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

  private final List<DoNotRetryItem> doNotRetry;

  private RpcRetryOptions(
      Duration initialInterval,
      double backoffCoefficient,
      Duration expiration,
      int maximumAttempts,
      Duration maximumInterval,
      List<DoNotRetryItem> doNotRetry) {
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

  public List<DoNotRetryItem> getDoNotRetry() {
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
    RpcRetryOptions that = (RpcRetryOptions) o;
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
}
