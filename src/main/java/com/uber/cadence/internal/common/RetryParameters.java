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

package com.uber.cadence.internal.common;

import com.uber.m3.util.ImmutableList;
import java.util.List;

public class RetryParameters {

  public int initialIntervalInSeconds;
  public double backoffCoefficient;
  public int maximumIntervalInSeconds;
  public int maximumAttempts;
  public List<String> nonRetriableErrorReasons;
  public int expirationIntervalInSeconds;

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

  public List<String> getNonRetriableErrorReasons() {
    return nonRetriableErrorReasons;
  }

  public void setNonRetriableErrorReasons(List<String> nonRetriableErrorReasons) {
    this.nonRetriableErrorReasons = nonRetriableErrorReasons;
  }

  public int getExpirationIntervalInSeconds() {
    return expirationIntervalInSeconds;
  }

  public void setExpirationIntervalInSeconds(int expirationIntervalInSeconds) {
    this.expirationIntervalInSeconds = expirationIntervalInSeconds;
  }

  public RetryParameters copy() {
    RetryParameters result = new RetryParameters();
    result.setMaximumIntervalInSeconds(maximumIntervalInSeconds);
    result.setNonRetriableErrorReasons(new ImmutableList<>(nonRetriableErrorReasons));
    result.setInitialIntervalInSeconds(initialIntervalInSeconds);
    result.setMaximumAttempts(maximumAttempts);
    result.setExpirationIntervalInSeconds(expirationIntervalInSeconds);
    result.setBackoffCoefficient(backoffCoefficient);
    return result;
  }
}
