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

package io.temporal.serviceclient.rpcretry;

import io.temporal.serviceclient.RpcRetryOptions;
import java.time.Duration;

/** Default rpc retry options for long polls like waiting for the workflow finishing and result. */
public class DefaultStubLongPollRpcRetryOptions {
  public static final Duration INITIAL_INTERVAL = Duration.ofMillis(50);
  public static final Duration CONGESTION_INITIAL_INTERVAL = Duration.ofMillis(1000);
  public static final Duration MAXIMUM_INTERVAL = Duration.ofMinutes(1);
  public static final double BACKOFF = 1.2;
  public static final double MAXIMUM_JITTER_COEFFICIENT = 0.1;

  // partial build because expiration is not set, long polls work with absolute deadlines instead
  public static final RpcRetryOptions INSTANCE = getBuilder().build();

  static {
    // retryer code that works with these options passes and accepts an absolute deadline
    // to ensure that the retry is finite
    INSTANCE.validate(false);
  }

  private static RpcRetryOptions.Builder getBuilder() {
    RpcRetryOptions.Builder roBuilder =
        RpcRetryOptions.newBuilder()
            .setInitialInterval(INITIAL_INTERVAL)
            .setCongestionInitialInterval(CONGESTION_INITIAL_INTERVAL)
            .setBackoffCoefficient(BACKOFF)
            .setMaximumInterval(MAXIMUM_INTERVAL)
            .setMaximumJitterCoefficient(MAXIMUM_JITTER_COEFFICIENT);

    return roBuilder;
  }
}
