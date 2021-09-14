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

package io.temporal.activity

import io.temporal.common.RetryOptions
import io.temporal.kotlin.TemporalDsl

/**
 * Options used to configure how an Activity is invoked.
 */
inline fun ActivityOptions(
  options: @TemporalDsl ActivityOptions.Builder.() -> Unit
): ActivityOptions {
  return ActivityOptions.newBuilder().apply(options).build()
}

/**
 * [RetryOptions] that define how Activity is retried in case of failure. If this is not set, then
 * the server-defined default Activity retry policy will be used. To ensure zero retries, set
 * maximum attempts to 1.
 */
inline fun ActivityOptions.Builder.setRetryOptions(
  retryOptions: @TemporalDsl RetryOptions.Builder.() -> Unit
) {
  setRetryOptions(RetryOptions(retryOptions))
}
