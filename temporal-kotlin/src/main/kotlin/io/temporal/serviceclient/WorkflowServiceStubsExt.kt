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

package io.temporal.serviceclient

import io.temporal.kotlin.TemporalDsl

/**
 * Create gRPC connection stubs using default options.
 *
 * @see WorkflowServiceStubs.newInstance
 */
fun WorkflowServiceStubs(): WorkflowServiceStubs {
  return WorkflowServiceStubs.newInstance()
}

/**
 * Create gRPC connection stubs using provided [options].
 *
 * @see WorkflowServiceStubs.newInstance
 */
inline fun WorkflowServiceStubs(
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit
): WorkflowServiceStubs {
  return WorkflowServiceStubs.newInstance(WorkflowServiceStubsOptions(options))
}
