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

package io.temporal.internal.common;

import static io.temporal.internal.common.OptionsUtils.roundUpToSeconds;

import com.uber.m3.util.ImmutableList;
import io.temporal.common.RetryOptions;
import io.temporal.common.v1.RetryPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RetryParameters {

  public int initialIntervalInSeconds;
  public double backoffCoefficient;
  public int maximumIntervalInSeconds;
  public int maximumAttempts;
  public List<String> nonRetriableErrorReasons;

  public RetryParameters(RetryOptions retryOptions) {
    setBackoffCoefficient(retryOptions.getBackoffCoefficient());
    setMaximumAttempts(retryOptions.getMaximumAttempts());
    setInitialIntervalInSeconds(roundUpToSeconds(retryOptions.getInitialInterval()));
    setMaximumIntervalInSeconds(roundUpToSeconds(retryOptions.getMaximumInterval()));
    List<String> types = new ArrayList<>(Arrays.asList(retryOptions.getDoNotRetry()));
    setNonRetriableErrorTypes(types);
  }

  public RetryParameters() {}

  public int getInitialIntervalInSeconds() {
    return initialIntervalInSeconds;
  }

  public void setInitialIntervalInSeconds(int initialIntervalInSeconds) {
    this.initialIntervalInSeconds = initialIntervalInSeconds;
  }

  public double getBackoffCoefficient() {
    return backoffCoefficient;
  }

  public void setBackoffCoefficient(double backoffCoefficient) {
    this.backoffCoefficient = backoffCoefficient;
  }

  public int getMaximumIntervalInSeconds() {
    return maximumIntervalInSeconds;
  }

  public void setMaximumIntervalInSeconds(int maximumIntervalInSeconds) {
    this.maximumIntervalInSeconds = maximumIntervalInSeconds;
  }

  public int getMaximumAttempts() {
    return maximumAttempts;
  }

  public void setMaximumAttempts(int maximumAttempts) {
    this.maximumAttempts = maximumAttempts;
  }

  public List<String> getNonRetriableErrorTypes() {
    return nonRetriableErrorReasons == null ? new ArrayList<>() : nonRetriableErrorReasons;
  }

  public void setNonRetriableErrorTypes(List<String> nonRetriableErrorReasons) {
    this.nonRetriableErrorReasons = nonRetriableErrorReasons;
  }

  public RetryParameters copy() {
    RetryParameters result = new RetryParameters();
    result.setMaximumIntervalInSeconds(maximumIntervalInSeconds);
    result.setNonRetriableErrorTypes(new ImmutableList<>(nonRetriableErrorReasons));
    result.setInitialIntervalInSeconds(initialIntervalInSeconds);
    result.setMaximumAttempts(maximumAttempts);
    result.setBackoffCoefficient(backoffCoefficient);
    return result;
  }

  public RetryPolicy toRetryPolicy() {
    if (getInitialIntervalInSeconds() <= 0) {
      throw new IllegalStateException(
          "required property initialIntervalSeconds is not set or valid:"
              + getInitialIntervalInSeconds());
    }
    return RetryPolicy.newBuilder()
        .addAllNonRetryableErrorTypes(getNonRetriableErrorTypes())
        .setMaximumAttempts(getMaximumAttempts())
        .setInitialIntervalInSeconds(getInitialIntervalInSeconds())
        .setBackoffCoefficient(getBackoffCoefficient())
        .setMaximumIntervalInSeconds(getMaximumIntervalInSeconds())
        .build();
  }

  @Override
  public String toString() {
    return "RetryParameters{"
        + "initialIntervalInSeconds="
        + initialIntervalInSeconds
        + ", backoffCoefficient="
        + backoffCoefficient
        + ", maximumIntervalInSeconds="
        + maximumIntervalInSeconds
        + ", maximumAttempts="
        + maximumAttempts
        + ", nonRetriableErrorReasons="
        + nonRetriableErrorReasons
        + '}';
  }
}
