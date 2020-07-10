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

package io.temporal.internal.testservice;

import io.grpc.Status;
import io.temporal.api.common.v1.RetryPolicy;
import io.temporal.api.enums.v1.RetryState;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class TestServiceRetryState {

  static class BackoffInterval {
    private final int intervalSeconds;
    private final RetryState retryState;

    BackoffInterval(int intervalSeconds) {
      this.intervalSeconds = intervalSeconds;
      this.retryState = RetryState.RETRY_STATE_IN_PROGRESS;
    }

    BackoffInterval(RetryState retryState) {
      this.intervalSeconds = -1;
      this.retryState = retryState;
    }

    public int getIntervalSeconds() {
      return intervalSeconds;
    }

    public RetryState getRetryState() {
      return retryState;
    }
  }

  private final RetryPolicy retryPolicy;
  private final long expirationTime;
  private final int attempt;

  TestServiceRetryState(RetryPolicy retryPolicy, long expirationTime) {
    this(valiateAndOverrideRetryPolicy(retryPolicy), expirationTime, 0);
  }

  private TestServiceRetryState(RetryPolicy retryPolicy, long expirationTime, int attempt) {
    this.retryPolicy = retryPolicy;
    this.expirationTime = expirationTime == 0 ? Long.MAX_VALUE : expirationTime;
    this.attempt = attempt;
  }

  RetryPolicy getRetryPolicy() {
    return retryPolicy;
  }

  long getExpirationTime() {
    return expirationTime;
  }

  int getAttempt() {
    return attempt;
  }

  TestServiceRetryState getNextAttempt() {
    return new TestServiceRetryState(retryPolicy, expirationTime, attempt + 1);
  }

  BackoffInterval getBackoffIntervalInSeconds(Optional<String> errorType, long currentTimeMillis) {
    RetryPolicy retryPolicy = getRetryPolicy();
    // check if error is non-retriable
    List<String> nonRetryableErrorTypes = retryPolicy.getNonRetryableErrorTypesList();
    if (nonRetryableErrorTypes != null && errorType.isPresent()) {
      String type = errorType.get();
      for (String err : nonRetryableErrorTypes) {
        if (type.equals(err)) {
          return new BackoffInterval(RetryState.RETRY_STATE_NON_RETRYABLE_FAILURE);
        }
      }
    }
    long expirationTime = getExpirationTime();
    if (retryPolicy.getMaximumAttempts() == 0 && expirationTime == 0) {
      return new BackoffInterval(RetryState.RETRY_STATE_RETRY_POLICY_NOT_SET);
    }

    if (retryPolicy.getMaximumAttempts() > 0
        && getAttempt() >= retryPolicy.getMaximumAttempts() - 1) {
      // currAttempt starts from 0.
      // MaximumAttempts is the total attempts, including initial (non-retry) attempt.
      return new BackoffInterval(RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED);
    }
    long initInterval = TimeUnit.SECONDS.toMillis(retryPolicy.getInitialIntervalInSeconds());
    long nextInterval =
        (long) (initInterval * Math.pow(retryPolicy.getBackoffCoefficient(), getAttempt()));
    long maxInterval = TimeUnit.SECONDS.toMillis(retryPolicy.getMaximumIntervalInSeconds());
    if (nextInterval <= 0) {
      // math.Pow() could overflow
      if (maxInterval > 0) {
        nextInterval = maxInterval;
      } else {
        return new BackoffInterval(RetryState.RETRY_STATE_TIMEOUT);
      }
    }

    if (maxInterval > 0 && nextInterval > maxInterval) {
      // cap next interval to MaxInterval
      nextInterval = maxInterval;
    }

    long backoffInterval = nextInterval;
    long nextScheduleTime = currentTimeMillis + backoffInterval;
    if (expirationTime != 0 && nextScheduleTime > expirationTime) {
      return new BackoffInterval(RetryState.RETRY_STATE_TIMEOUT);
    }
    int result = (int) TimeUnit.MILLISECONDS.toSeconds((long) Math.ceil((double) backoffInterval));
    return new BackoffInterval(result);
  }

  static RetryPolicy valiateAndOverrideRetryPolicy(RetryPolicy p) {
    RetryPolicy.Builder policy = p.toBuilder();
    if (policy.getInitialIntervalInSeconds() < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("InitialIntervalInSeconds must be greater than 0 on retry policy.")
          .asRuntimeException();
    }
    if (policy.getInitialIntervalInSeconds() == 0) {
      policy.setInitialIntervalInSeconds(1);
    }
    if (policy.getBackoffCoefficient() != 0 && policy.getBackoffCoefficient() < 1) {
      throw Status.INVALID_ARGUMENT
          .withDescription("BackoffCoefficient cannot be less than 1 on retry policy.")
          .asRuntimeException();
    }
    if (policy.getBackoffCoefficient() == 0) {
      policy.setBackoffCoefficient(2d);
    }
    if (policy.getMaximumIntervalInSeconds() < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("MaximumIntervalInSeconds cannot be less than 0 on retry policy.")
          .asRuntimeException();
    }
    if (policy.getMaximumIntervalInSeconds() > 0
        && policy.getMaximumIntervalInSeconds() < policy.getInitialIntervalInSeconds()) {
      throw Status.INVALID_ARGUMENT
          .withDescription(
              "MaximumIntervalInSeconds cannot be less than InitialIntervalInSeconds on retry policy.")
          .asRuntimeException();
    }
    if (policy.getMaximumAttempts() < 0) {
      throw Status.INVALID_ARGUMENT
          .withDescription("MaximumAttempts cannot be less than 0 on retry policy.")
          .asRuntimeException();
    }
    return policy.build();
  }
}
