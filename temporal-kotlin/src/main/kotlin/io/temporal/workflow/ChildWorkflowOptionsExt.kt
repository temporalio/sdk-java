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

package io.temporal.workflow

import io.temporal.common.RetryOptions
import io.temporal.kotlin.TemporalDsl

/**
 * @see ChildWorkflowOptions
 */
inline fun ChildWorkflowOptions(
  options: @TemporalDsl ChildWorkflowOptions.Builder.() -> Unit
): ChildWorkflowOptions {
  return ChildWorkflowOptions.newBuilder().apply(options).build()
}

/**
 * Create a new instance of [ChildWorkflowOptions], optionally overriding some of its properties.
 */
inline fun ChildWorkflowOptions.copy(
  overrides: @TemporalDsl ChildWorkflowOptions.Builder.() -> Unit
): ChildWorkflowOptions {
  return toBuilder().apply(overrides).build()
}

/**
 * @see ChildWorkflowOptions.Builder.setRetryOptions
 * @see ChildWorkflowOptions.getRetryOptions
 */
inline fun ChildWorkflowOptions.Builder.setRetryOptions(
  retryOptions: @TemporalDsl RetryOptions.Builder.() -> Unit
) {
  setRetryOptions(RetryOptions(retryOptions))
}
