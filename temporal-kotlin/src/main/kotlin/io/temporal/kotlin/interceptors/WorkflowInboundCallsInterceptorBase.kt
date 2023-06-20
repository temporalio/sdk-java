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

package io.temporal.kotlin.interceptors

/** Convenience base class for WorkflowInboundCallsInterceptor implementations.  */
class WorkflowInboundCallsInterceptorBase(private val next: WorkflowInboundCallsInterceptor) :
  WorkflowInboundCallsInterceptor {
  override suspend fun init(outboundCalls: WorkflowOutboundCallsInterceptor) {
    next.init(outboundCalls)
  }

  override suspend fun execute(input: WorkflowInboundCallsInterceptor.WorkflowInput): WorkflowInboundCallsInterceptor.WorkflowOutput {
    return next.execute(input)
  }

  override suspend fun handleSignal(input: WorkflowInboundCallsInterceptor.SignalInput) {
    next.handleSignal(input)
  }

  override fun handleQuery(input: WorkflowInboundCallsInterceptor.QueryInput): WorkflowInboundCallsInterceptor.QueryOutput {
    return next.handleQuery(input)
  }

  override fun validateUpdate(input: WorkflowInboundCallsInterceptor.UpdateInput) {
    next.validateUpdate(input)
  }

  override suspend fun executeUpdate(input: WorkflowInboundCallsInterceptor.UpdateInput): WorkflowInboundCallsInterceptor.UpdateOutput {
    return next.executeUpdate(input)
  }
}
