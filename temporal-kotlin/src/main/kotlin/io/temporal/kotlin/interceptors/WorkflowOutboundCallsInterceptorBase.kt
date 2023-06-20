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

import io.temporal.common.SearchAttributeUpdate
import io.temporal.workflow.Functions.Func
import java.lang.reflect.Type
import java.time.Duration
import java.util.*
import java.util.function.BiPredicate
import java.util.function.Supplier

/** Convenience base class for WorkflowOutboundCallsInterceptor implementations.  */
class WorkflowOutboundCallsInterceptorBase(private val next: WorkflowOutboundCallsInterceptor) :
  WorkflowOutboundCallsInterceptor {
  override suspend fun <R> executeActivity(input: WorkflowOutboundCallsInterceptor.ActivityInput<R>): WorkflowOutboundCallsInterceptor.ActivityOutput<R?> {
    return next.executeActivity(input)
  }

  override suspend fun <R> executeLocalActivity(input: WorkflowOutboundCallsInterceptor.LocalActivityInput<R>): WorkflowOutboundCallsInterceptor.LocalActivityOutput<R?> {
    return next.executeLocalActivity(input)
  }

  override suspend fun <R> executeChildWorkflow(input: WorkflowOutboundCallsInterceptor.ChildWorkflowInput<R>): WorkflowOutboundCallsInterceptor.ChildWorkflowOutput<R?> {
    return next.executeChildWorkflow(input)
  }

  override fun newRandom(): Random {
    return next.newRandom()
  }

  override suspend fun signalExternalWorkflow(input: WorkflowOutboundCallsInterceptor.SignalExternalInput): WorkflowOutboundCallsInterceptor.SignalExternalOutput {
    return next.signalExternalWorkflow(input)
  }

  override fun cancelWorkflow(input: WorkflowOutboundCallsInterceptor.CancelWorkflowInput): WorkflowOutboundCallsInterceptor.CancelWorkflowOutput {
    return next.cancelWorkflow(input)
  }

  override suspend fun sleep(duration: Duration) {
    return next.sleep(duration)
  }

  override suspend fun await(timeout: Duration, reason: String?, unblockCondition: Supplier<Boolean?>): Boolean {
    return next.await(timeout, reason, unblockCondition)
  }

  override suspend fun await(reason: String?, unblockCondition: Supplier<Boolean?>) {
    return next.await(reason, unblockCondition)
  }

  override fun <R> sideEffect(resultClass: Class<R>, resultType: Type, func: Func<R?>): R? {
    return next.sideEffect(resultClass, resultType, func)
  }

  override fun <R> mutableSideEffect(
    id: String,
    resultClass: Class<R>,
    resultType: Type,
    updated: BiPredicate<R?, R?>,
    func: Func<R?>
  ): R? {
    return next.mutableSideEffect(id, resultClass, resultType, updated, func)
  }

  override fun getVersion(changeId: String, minSupported: Int, maxSupported: Int): Int {
    return next.getVersion(changeId, minSupported, maxSupported)
  }

  override fun continueAsNew(input: WorkflowOutboundCallsInterceptor.ContinueAsNewInput) {
    return next.continueAsNew(input)
  }

  override fun registerQuery(input: WorkflowOutboundCallsInterceptor.RegisterQueryInput) {
    return next.registerQuery(input)
  }

  override fun registerSignalHandlers(input: WorkflowOutboundCallsInterceptor.RegisterSignalHandlersInput) {
    return next.registerSignalHandlers(input)
  }

  override fun registerUpdateHandlers(input: WorkflowOutboundCallsInterceptor.RegisterUpdateHandlersInput) {
    return next.registerUpdateHandlers(input)
  }

  override fun registerDynamicSignalHandler(input: WorkflowOutboundCallsInterceptor.RegisterDynamicSignalHandlerInput) {
    return next.registerDynamicSignalHandler(input)
  }

  override fun registerDynamicQueryHandler(input: WorkflowOutboundCallsInterceptor.RegisterDynamicQueryHandlerInput) {
    return next.registerDynamicQueryHandler(input)
  }

  override fun registerDynamicUpdateHandler(input: WorkflowOutboundCallsInterceptor.RegisterDynamicUpdateHandlerInput) {
    return next.registerDynamicUpdateHandler(input)
  }

  override fun randomUUID(): UUID {
    return next.randomUUID()
  }

  override fun upsertSearchAttributes(searchAttributes: Map<String?, *>) {
    return next.upsertSearchAttributes(searchAttributes)
  }

  override fun upsertTypedSearchAttributes(searchAttributeUpdates: List<SearchAttributeUpdate<*>>) {
    return next.upsertTypedSearchAttributes(searchAttributeUpdates)
  }

  override fun currentTimeMillis(): Long {
    return next.currentTimeMillis()
  }
}
