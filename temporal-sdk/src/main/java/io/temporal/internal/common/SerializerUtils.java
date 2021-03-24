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

import io.temporal.api.common.v1.RetryPolicy;
import io.temporal.common.RetryOptions;
import java.util.Arrays;

public class SerializerUtils {
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
