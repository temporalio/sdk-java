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

package com.uber.cadence.internal.testservice;

import com.uber.cadence.BadRequestError;
import com.uber.cadence.RetryPolicy;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class RetryState {

  private final RetryPolicy retryPolicy;
  private final long expirationTime;
  private final int attempt;

  RetryState(RetryPolicy retryPolicy, long expirationTime) throws BadRequestError {
    this(validateRetryPolicy(retryPolicy), expirationTime, 0);
  }

  private RetryState(RetryPolicy retryPolicy, long expirationTime, int attempt) {
    this.retryPolicy = retryPolicy;
    this.expirationTime =
        retryPolicy.getExpirationIntervalInSeconds() == 0 ? Long.MAX_VALUE : expirationTime;
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

  int getBackoffIntervalInSeconds(String errReason, long currentTimeMillis) {
    RetryPolicy retryPolicy = getRetryPolicy();
    long expirationTime = getExpirationTime();
    if (retryPolicy.getMaximumAttempts() == 0 && expirationTime == 0) {
      return 0;
    }

    if (retryPolicy.getMaximumAttempts() > 0
        && getAttempt() >= retryPolicy.getMaximumAttempts() - 1) {
      // currAttempt starts from 0.
      // MaximumAttempts is the total attempts, including initial (non-retry) attempt.
      return 0;
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
        return 0;
      }
    }

    if (maxInterval > 0 && nextInterval > maxInterval) {
      // cap next interval to MaxInterval
      nextInterval = maxInterval;
    }

    long backoffInterval = nextInterval;
    long nextScheduleTime = currentTimeMillis + backoffInterval;
    if (expirationTime != 0 && nextScheduleTime > expirationTime) {
      return 0;
    }

    // check if error is non-retriable
    List<String> nonRetriableErrorReasons = retryPolicy.getNonRetriableErrorReasons();
    if (nonRetriableErrorReasons != null) {
      for (String err : nonRetriableErrorReasons) {
        if (errReason.equals(err)) {
          return 0;
        }
      }
    }
    return (int) TimeUnit.MILLISECONDS.toSeconds((long) Math.ceil((double) backoffInterval));
  }

  static RetryPolicy validateRetryPolicy(RetryPolicy policy) throws BadRequestError {
    if (policy.getInitialIntervalInSeconds() <= 0) {
      throw new BadRequestError("InitialIntervalInSeconds must be greater than 0 on retry policy.");
    }
    if (policy.getBackoffCoefficient() < 1) {
      throw new BadRequestError("BackoffCoefficient cannot be less than 1 on retry policy.");
    }
    if (policy.getMaximumIntervalInSeconds() < 0) {
      throw new BadRequestError("MaximumIntervalInSeconds cannot be less than 0 on retry policy.");
    }
    if (policy.getMaximumIntervalInSeconds() > 0
        && policy.getMaximumIntervalInSeconds() < policy.getInitialIntervalInSeconds()) {
      throw new BadRequestError(
          "MaximumIntervalInSeconds cannot be less than InitialIntervalInSeconds on retry policy.");
    }
    if (policy.getMaximumAttempts() < 0) {
      throw new BadRequestError("MaximumAttempts cannot be less than 0 on retry policy.");
    }
    if (policy.getMaximumAttempts() == 0 && policy.getExpirationIntervalInSeconds() == 0) {
      throw new BadRequestError(
          "MaximumAttempts and ExpirationIntervalInSeconds are both 0. At least one of them must be specified.");
    }
    return policy;
  }
}
