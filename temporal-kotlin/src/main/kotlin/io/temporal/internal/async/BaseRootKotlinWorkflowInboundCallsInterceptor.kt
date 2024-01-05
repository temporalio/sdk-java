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

package io.temporal.internal.async

import io.temporal.kotlin.interceptors.WorkflowInboundCallsInterceptor
import io.temporal.kotlin.interceptors.WorkflowOutboundCallsInterceptor

/**
 * Provides core functionality for a root WorkflowInboundCallsInterceptor that is reused by specific
 * root RootWorkflowInboundCallsInterceptor implementations inside [ ] and [POJOWorkflowImplementationFactory]
 *
 *
 * Root `WorkflowInboundCallsInterceptor` is an interceptor that should be at the end of
 * the [WorkflowInboundCallsInterceptor] interceptors chain and which encapsulates calls into
 * Temporal internals while providing a WorkflowInboundCallsInterceptor interface for chaining on
 * top of it.
 */
abstract class BaseRootKotlinWorkflowInboundCallsInterceptor(protected val workflowContext: KotlinWorkflowContext) :
  WorkflowInboundCallsInterceptor {
  override suspend fun init(outboundCalls: WorkflowOutboundCallsInterceptor) {
    workflowContext.initHeadOutboundCallsInterceptor(outboundCalls)
  }

  override suspend fun handleSignal(input: WorkflowInboundCallsInterceptor.SignalInput) {
    TODO("Not yet implemented")
    //        workflowContext.handleInterceptedSignal(input)
  }

  override fun handleQuery(input: WorkflowInboundCallsInterceptor.QueryInput): WorkflowInboundCallsInterceptor.QueryOutput {
    TODO("Implement")
//        return workflowContext.handleInterceptedQuery(input)
  }

  override fun validateUpdate(input: WorkflowInboundCallsInterceptor.UpdateInput) {
    TODO("Implement")
//        workflowContext.handleInterceptedValidateUpdate(input)
  }

  override suspend fun executeUpdate(input: WorkflowInboundCallsInterceptor.UpdateInput): WorkflowInboundCallsInterceptor.UpdateOutput {
    TODO("Implement")
//      return workflowContext.handleInterceptedExecuteUpdate(input)
  }
}
