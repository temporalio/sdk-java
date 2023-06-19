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

import io.temporal.api.common.v1.Payloads
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.EncodedValues
import io.temporal.common.converter.Values
import io.temporal.common.interceptors.Header
import io.temporal.internal.sync.WorkflowInternal
import io.temporal.kotlin.interceptors.KotlinWorkerInterceptor
import io.temporal.kotlin.interceptors.WorkflowInboundCallsInterceptor
import io.temporal.kotlin.interceptors.WorkflowOutboundCallsInterceptor
import io.temporal.kotlin.workflow.KotlinDynamicWorkflow
import io.temporal.workflow.Functions.Func

internal class DynamicKotlinWorkflowDefinition(
  private val factory: Func<out KotlinDynamicWorkflow>,
  private val workerInterceptors: Array<KotlinWorkerInterceptor>,
  private val dataConverter: DataConverter
) : KotlinWorkflowDefinition {
  private var workflowInvoker: WorkflowInboundCallsInterceptor? = null

  override suspend fun initialize() {
    val workflowContext: KotlinWorkflowContext = KotlinWorkflowInternal.rootWorkflowContext
    workflowInvoker = RootWorkflowInboundCallsInterceptor(workflowContext)
    for (workerInterceptor in workerInterceptors) {
      workflowInvoker = workerInterceptor.interceptWorkflow(workflowInvoker!!)
    }
    workflowContext.initHeadInboundCallsInterceptor(workflowInvoker!!)
    workflowInvoker!!.init(workflowContext)
  }

  override suspend fun execute(header: Header?, input: Payloads?): Payloads? {
    val args: Values = EncodedValues(input, dataConverter)
    val result = workflowInvoker!!.execute(
      WorkflowInboundCallsInterceptor.WorkflowInput(header!!, arrayOf(args))
    )
    return dataConverter.toPayloads(result.result).orElse(null)
  }

  internal inner class RootWorkflowInboundCallsInterceptor(workflowContext: KotlinWorkflowContext?) :
    BaseRootKotlinWorkflowInboundCallsInterceptor(
      workflowContext!!
    ) {
    private var workflow: KotlinDynamicWorkflow? = null

    override suspend fun init(outboundCalls: WorkflowOutboundCallsInterceptor) {
      super.init(outboundCalls)
      newInstance()
      WorkflowInternal.registerListener(workflow)
    }

    override suspend fun execute(input: WorkflowInboundCallsInterceptor.WorkflowInput): WorkflowInboundCallsInterceptor.WorkflowOutput {
      val result = workflow!!.execute(input.arguments[0] as EncodedValues)
      return WorkflowInboundCallsInterceptor.WorkflowOutput(result)
    }

    private fun newInstance() {
      check(workflow == null) { "Already called" }
      workflow = factory.apply()
    }
  }
}
