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

import io.grpc.Status;
import io.temporal.api.errordetails.v1.QueryFailedFailure;
import java.time.Duration;

public class DefaultServiceOperationRpcRetryOptions {
  public static final Duration INITIAL_INTERVAL = Duration.ofMillis(20);
  public static final Duration EXPIRATION_INTERVAL = Duration.ofMinutes(1);
  public static final Duration MAXIMUM_INTERVAL;
  public static final double BACKOFF = 1.2;

  public static final RpcRetryOptions INSTANCE;

  static {
    Duration maxInterval = EXPIRATION_INTERVAL.dividedBy(10);
    if (maxInterval.compareTo(INITIAL_INTERVAL) < 0) {
      maxInterval = INITIAL_INTERVAL;
    }
    MAXIMUM_INTERVAL = maxInterval;

    INSTANCE = getDefaultServiceOperationRetryOptionsBuilder().validateBuildWithDefaults();
  }

  private static RpcRetryOptions.Builder getDefaultServiceOperationRetryOptionsBuilder() {
    RpcRetryOptions.Builder roBuilder =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(INITIAL_INTERVAL)
            .setExpiration(EXPIRATION_INTERVAL)
            .setBackoffCoefficient(BACKOFF)
            .setMaximumInterval(MAXIMUM_INTERVAL);
    // CANCELLED and DEADLINE_EXCEEDED are always considered non-retryable
    roBuilder
        .addDoNotRetry(Status.Code.INVALID_ARGUMENT, null)
        .addDoNotRetry(Status.Code.NOT_FOUND, null)
        .addDoNotRetry(Status.Code.ALREADY_EXISTS, null)
        .addDoNotRetry(Status.Code.FAILED_PRECONDITION, null)
        .addDoNotRetry(Status.Code.PERMISSION_DENIED, null)
        .addDoNotRetry(Status.Code.UNAUTHENTICATED, null)
        .addDoNotRetry(Status.Code.UNIMPLEMENTED, null)
        .addDoNotRetry(Status.Code.INTERNAL, QueryFailedFailure.class);
    return roBuilder;
  }
}
