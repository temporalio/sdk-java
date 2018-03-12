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

package com.uber.cadence.activity;

import com.uber.cadence.common.RetryOptions;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;

/**
 * Indicates retry policy for a workflow or activity method. This annotation applies only to
 * activity or workflow interface methods. For workflows currently used only for child workflow
 * retries. Not required. When not used either retries don't happen or they are configured through
 * correspondent options. If {@link RetryOptions} are present on {@link ActivityOptions} or {@link
 * com.uber.cadence.workflow.ChildWorkflowOptions} the fields that are not default take precedence
 * over parameters of this annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MethodRetry {

  /**
   * Interval of the first retry. If coefficient is 1.0 then it is used for all retries. Required.
   * Can be overridden through {@link RetryOptions.Builder#setInitialInterval(Duration)}.
   */
  long initialIntervalSeconds();

  /**
   * Maximum time to retry. Default is forever. When exceeded the retries stop even if maximum
   * retries is not reached yet.
   */
  long expirationSeconds() default 0;

  /**
   * Coefficient used to calculate the next retry interval. The next retry interval is previous
   * interval multiplied by this coefficient. Must be 1 or larger. Default is 2.0.
   */
  double backoffCoefficient() default 0;

  /**
   * Maximum number of attempts. When exceeded the retries stop even if not expired yet. Must be 1
   * or bigger. Default is unlimited.
   */
  int maximumAttempts() default 0;

  /**
   * Minimum number of retries. Even if expired will retry until this number is reached. Must be 1
   * or bigger. Default is 0.
   */
  int minimumAttempts() default 0;

  /**
   * Maximum interval between retries. Exponential backoff leads to interval increase. This value is
   * the cap of the increase. Default is 100x {@link #initialIntervalSeconds()}.
   */
  long maximumIntervalSeconds() default 0;

  /**
   * List of exceptions to retry. When matching an exact match is used. So adding
   * RuntimeException.class to this list is going to include only RuntimeException itself, not all
   * of its subclasses. The reason for such behaviour is to be able to support server side retries
   * without knowledge of Java exception hierarchy. {@link Error} and {@link
   * java.util.concurrent.CancellationException} are never retried, so they are not allowed in this
   * list.
   */
  Class<? extends Throwable>[] doNotRetry() default {};
}
