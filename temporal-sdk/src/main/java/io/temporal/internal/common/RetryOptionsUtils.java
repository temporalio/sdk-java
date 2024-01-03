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

package io.temporal.internal.common;

import io.grpc.Deadline;
import io.temporal.api.common.v1.RetryPolicy;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.ChildWorkflowFailure;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

public class RetryOptionsUtils {
  public static boolean isNotRetryable(RetryOptions o, @Nullable Throwable e) {
    if (e == null) {
      return false;
    }
    if (e instanceof ActivityFailure || e instanceof ChildWorkflowFailure) {
      e = e.getCause();
    }
    String type =
        e instanceof ApplicationFailure
            ? ((ApplicationFailure) e).getType()
            : e.getClass().getName();
    return isNotRetryable(o, type);
  }

  public static boolean isNotRetryable(RetryOptions o, @Nullable String type) {
    if (type == null) {
      return false;
    }
    if (o.getDoNotRetry() != null) {
      for (String doNotRetry : o.getDoNotRetry()) {
        if (doNotRetry.equals(type)) {
          return true;
        }
      }
    }
    return false;
  }

  public static boolean areAttemptsReached(RetryOptions o, long attempt) {
    return (o.getMaximumAttempts() != 0 && attempt >= o.getMaximumAttempts());
  }

  public static boolean isDeadlineReached(@Nullable Deadline deadline, long sleepTimeMs) {
    return deadline != null && deadline.timeRemaining(TimeUnit.MILLISECONDS) < sleepTimeMs;
  }

  public static RetryOptions toRetryOptions(RetryPolicy retryPolicy) {
    RetryOptions.Builder roBuilder = RetryOptions.newBuilder();

    Duration maximumInterval = ProtobufTimeUtils.toJavaDuration(retryPolicy.getMaximumInterval());
    if (!maximumInterval.isZero()) {
      roBuilder.setMaximumInterval(maximumInterval);
    }

    Duration initialInterval = ProtobufTimeUtils.toJavaDuration(retryPolicy.getInitialInterval());
    if (!initialInterval.isZero()) {
      roBuilder.setInitialInterval(initialInterval);
    }

    if (retryPolicy.getBackoffCoefficient() >= 1) {
      roBuilder.setBackoffCoefficient(retryPolicy.getBackoffCoefficient());
    }

    if (retryPolicy.getMaximumAttempts() > 0) {
      roBuilder.setMaximumAttempts(retryPolicy.getMaximumAttempts());
    }

    roBuilder.setDoNotRetry(
        retryPolicy
            .getNonRetryableErrorTypesList()
            .toArray(new String[retryPolicy.getNonRetryableErrorTypesCount()]));

    return roBuilder.validateBuildWithDefaults();
  }

  public static RetryPolicy.Builder toRetryPolicy(RetryOptions retryOptions) {
    RetryPolicy.Builder builder =
        RetryPolicy.newBuilder()
            .setInitialInterval(
                ProtobufTimeUtils.toProtoDuration(retryOptions.getInitialInterval()))
            .setMaximumInterval(
                ProtobufTimeUtils.toProtoDuration(retryOptions.getMaximumInterval()))
            .setBackoffCoefficient(retryOptions.getBackoffCoefficient())
            .setMaximumAttempts(retryOptions.getMaximumAttempts());

    if (retryOptions.getDoNotRetry() != null) {
      builder = builder.addAllNonRetryableErrorTypes(Arrays.asList(retryOptions.getDoNotRetry()));
    }

    return builder;
  }
}
