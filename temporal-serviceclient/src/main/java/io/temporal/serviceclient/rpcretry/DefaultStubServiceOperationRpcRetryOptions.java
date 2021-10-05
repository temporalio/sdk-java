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

package io.temporal.serviceclient.rpcretry;

import io.grpc.Status;
import io.temporal.serviceclient.RpcRetryOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Default rpc retry options for outgoing requests to the temporal server that supposed to be
 * processed and returned fast, like workflow start (not long polls or awaits for workflow
 * finishing).
 */
public class DefaultStubServiceOperationRpcRetryOptions {
  public static final Duration INITIAL_INTERVAL = Duration.ofMillis(50);
  public static final Duration EXPIRATION_INTERVAL = Duration.ofMinutes(1);
  public static final Duration MAXIMUM_INTERVAL;
  public static final double BACKOFF = 2;

  public static final List<RpcRetryOptions.DoNotRetryItem> TEMPORAL_SERVER_DEFAULT_NON_RETRY =
      new ArrayList<RpcRetryOptions.DoNotRetryItem>() {
        {
          // CANCELLED is always considered non-retryable
          // DEADLINE_EXCEEDED is handled in a special way (retry till the root gRPC context is
          // expired)
          add(new RpcRetryOptions.DoNotRetryItem(Status.Code.INVALID_ARGUMENT, null));
          add(new RpcRetryOptions.DoNotRetryItem(Status.Code.NOT_FOUND, null));
          add(new RpcRetryOptions.DoNotRetryItem(Status.Code.ALREADY_EXISTS, null));
          add(new RpcRetryOptions.DoNotRetryItem(Status.Code.FAILED_PRECONDITION, null));
          add(new RpcRetryOptions.DoNotRetryItem(Status.Code.PERMISSION_DENIED, null));
          add(new RpcRetryOptions.DoNotRetryItem(Status.Code.UNAUTHENTICATED, null));
          add(new RpcRetryOptions.DoNotRetryItem(Status.Code.UNIMPLEMENTED, null));
        }
      };

  public static final RpcRetryOptions INSTANCE;

  static {
    Duration maxInterval = EXPIRATION_INTERVAL.dividedBy(10);
    if (maxInterval.compareTo(INITIAL_INTERVAL) < 0) {
      maxInterval = INITIAL_INTERVAL;
    }
    MAXIMUM_INTERVAL = maxInterval;

    INSTANCE = getBuilder().validateBuildWithDefaults();
  }

  public static RpcRetryOptions.Builder getBuilder() {
    RpcRetryOptions.Builder roBuilder =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(INITIAL_INTERVAL)
            .setExpiration(EXPIRATION_INTERVAL)
            .setBackoffCoefficient(BACKOFF)
            .setMaximumInterval(MAXIMUM_INTERVAL);
    TEMPORAL_SERVER_DEFAULT_NON_RETRY.forEach(roBuilder::addDoNotRetry);
    return roBuilder;
  }
}
