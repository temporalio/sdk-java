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

package io.temporal.serviceclient

import io.temporal.kotlin.TemporalDsl
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

/**
 * Create gRPC connection stubs using default options.
 *
 * @see WorkflowServiceStubs.newInstance
 */
@Deprecated("Use LocalWorkflowServiceStubs()", replaceWith = ReplaceWith("LocalWorkflowServiceStubs()"))
fun WorkflowServiceStubs(): WorkflowServiceStubs {
  @Suppress("DEPRECATION")
  return WorkflowServiceStubs.newInstance()
}

/**
 * Create gRPC connection stubs using provided [options].
 *
 * @see WorkflowServiceStubs.newInstance
 */
@Deprecated("Use LazyWorkflowServiceStubs(options) or ConnectedWorkflowServiceStubs(options)")
inline fun WorkflowServiceStubs(
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit
): WorkflowServiceStubs {
  @Suppress("DEPRECATION")
  return WorkflowServiceStubs.newInstance(WorkflowServiceStubsOptions(options))
}

/**
 * Create WorkflowService gRPC stubs pointed on to the locally running Temporal Server.
 *
 * @see WorkflowServiceStubs.newLocalServiceStubs
 */
fun LocalWorkflowServiceStubs(): WorkflowServiceStubs {
  return WorkflowServiceStubs.newLocalServiceStubs()
}

/**
 * Create WorkflowService gRPC stubs using provided [options].
 *
 * @see WorkflowServiceStubs.newServiceStubs
 */
inline fun LazyWorkflowServiceStubs(
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit
): WorkflowServiceStubs {
  return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions(options))
}

/**
 * Create WorkflowService gRPC stubs using provided [options].
 *
 * @see WorkflowServiceStubs.newConnectedServiceStubs
 */
@ExperimentalTime
@Deprecated(
  "Use ConnectedWorkflowServiceStubs(timeout, options)",
  ReplaceWith("ConnectedWorkflowServiceStubs(timeout, options)")
)
inline fun ConnectedWorkflowServiceStubs(
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit,
  timeout: Duration
): WorkflowServiceStubs {
  return ConnectedWorkflowServiceStubs(timeout, options)
}

/**
 * Create WorkflowService gRPC stubs using provided [options].
 *
 * @see WorkflowServiceStubs.newConnectedServiceStubs
 */
@ExperimentalTime
inline fun ConnectedWorkflowServiceStubs(
  timeout: Duration,
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit
): WorkflowServiceStubs {
  return ConnectedWorkflowServiceStubs(timeout.toJavaDuration(), options)
}

/**
 * Create WorkflowService gRPC stubs using provided [options].
 *
 * @see WorkflowServiceStubs.newConnectedServiceStubs
 */
inline fun ConnectedWorkflowServiceStubs(
  timeout: JavaDuration? = null,
  options: @TemporalDsl WorkflowServiceStubsOptions.Builder.() -> Unit
): WorkflowServiceStubs {
  return WorkflowServiceStubs.newConnectedServiceStubs(WorkflowServiceStubsOptions(options), timeout)
}
