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

package io.temporal.activity

import io.temporal.common.RetryOptions
import io.temporal.kotlin.TemporalDsl

/**
 * Options used to configure how a local Activity is invoked.
 *
 * @see LocalActivityOptions
 */
inline fun LocalActivityOptions(
  options: @TemporalDsl LocalActivityOptions.Builder.() -> Unit
): LocalActivityOptions {
  return LocalActivityOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [LocalActivityOptions], optionally overriding some of its properties.
 */
inline fun LocalActivityOptions.copy(
  overrides: @TemporalDsl LocalActivityOptions.Builder.() -> Unit
): LocalActivityOptions {
  return toBuilder().apply(overrides).build()
}

/**
 * @see LocalActivityOptions.Builder.setRetryOptions
 * @see LocalActivityOptions.getRetryOptions
 */
inline fun @TemporalDsl LocalActivityOptions.Builder.setRetryOptions(
  retryOptions: @TemporalDsl RetryOptions.Builder.() -> Unit
) {
  setRetryOptions(RetryOptions(retryOptions))
}
