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
import io.temporal.proto.common.RetryPolicy;
import io.temporal.proto.common.RetryStatus;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class RetryState {

  static class BackoffInterval {
    private final int intervalSeconds;
    private final RetryStatus retryStatus;

    BackoffInterval(int intervalSeconds) {
      this.intervalSeconds = intervalSeconds;
      this.retryStatus = RetryStatus.InProgress;
    }

    BackoffInterval(RetryStatus retryStatus) {
      this.intervalSeconds = -1;
      this.retryStatus = retryStatus;
    }

    public int getIntervalSeconds() {
      return intervalSeconds;
    }

    public RetryStatus getRetryStatus() {
      return retryStatus;
    }
  }

  private final RetryPolicy retryPolicy;
  private final long expirationTime;
  private final int attempt;

  RetryState(RetryPolicy retryPolicy, long expirationTime) {
    this(valiateAndOverrideRetryPolicy(retryPolicy), expirationTime, 0);
  }

  private RetryState(RetryPolicy retryPolicy, long expirationTime, int attempt) {
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

  RetryState getNextAttempt() {
    return new RetryState(retryPolicy, expirationTime, attempt + 1);
  }

  BackoffInterval getBackoffIntervalInSeconds(Optional<String> errorType, long currentTimeMillis) {
    RetryPolicy retryPolicy = getRetryPolicy();
    // check if error is non-retriable
    List<String> nonRetryableErrorTypes = retryPolicy.getNonRetryableErrorTypesList();
    if (nonRetryableErrorTypes != null && errorType.isPresent()) {
      String type = errorType.get();
      for (String err : nonRetryableErrorTypes) {
        if (type.equals(err)) {
          return new BackoffInterval(RetryStatus.NonRetryableFailure);
        }
      }
    }
    long expirationTime = getExpirationTime();
    if (retryPolicy.getMaximumAttempts() == 0 && expirationTime == 0) {
      return new BackoffInterval(RetryStatus.RetryPolicyNotSet);
    }

    if (retryPolicy.getMaximumAttempts() > 0
        && getAttempt() >= retryPolicy.getMaximumAttempts() - 1) {
      // currAttempt starts from 0.
      // MaximumAttempts is the total attempts, including initial (non-retry) attempt.
      return new BackoffInterval(RetryStatus.MaximumAttemptsReached);
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
        return new BackoffInterval(RetryStatus.Timeout);
      }
    }

    if (maxInterval > 0 && nextInterval > maxInterval) {
      // cap next interval to MaxInterval
      nextInterval = maxInterval;
    }

    long backoffInterval = nextInterval;
    long nextScheduleTime = currentTimeMillis + backoffInterval;
    if (expirationTime != 0 && nextScheduleTime > expirationTime) {
      return new BackoffInterval(RetryStatus.Timeout);
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
