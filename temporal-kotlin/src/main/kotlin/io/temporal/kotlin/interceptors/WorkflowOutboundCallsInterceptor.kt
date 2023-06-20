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

import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.common.Experimental
import io.temporal.common.SearchAttributeUpdate
import io.temporal.common.interceptors.Header
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.ContinueAsNewOptions
import io.temporal.workflow.DynamicQueryHandler
import io.temporal.workflow.DynamicSignalHandler
import io.temporal.workflow.DynamicUpdateHandler
import io.temporal.workflow.Functions.Func
import io.temporal.workflow.Functions.Func1
import io.temporal.workflow.Functions.Proc1
import io.temporal.workflow.Promise
import java.lang.reflect.Type
import java.time.Duration
import java.util.*
import java.util.function.BiPredicate
import java.util.function.Supplier

/**
 * Can be used to intercept calls from to workflow code into the Temporal APIs.
 *
 *
 * The calls to the interceptor are executed in the context of a workflow and must follow the
 * same rules all the other workflow code follows.
 *
 *
 * Prefer extending [WorkflowOutboundCallsInterceptorBase] and overriding only the methods
 * you need instead of implementing this interface directly. [ ] provides correct default implementations to all the methods
 * of this interface.
 *
 *
 * An instance may be created in [ ][WorkflowInboundCallsInterceptor.init] and set by passing it
 * into `init` method of the `next` [WorkflowInboundCallsInterceptor] The
 * implementation must forward all the calls to the outbound interceptor passed as a `outboundCalls` parameter to the `init` call.
 *
 * @see KotlinWorkerInterceptor.interceptWorkflow for the definition of "next" {@link
 * *     WorkflowInboundCallsInterceptor}.
 */
@Experimental
interface WorkflowOutboundCallsInterceptor {
  class ActivityInput<R>(
    val activityName: String,
    val resultClass: Class<R>,
    val resultType: Type,
    val args: Array<out Any>,
    val options: ActivityOptions,
    val header: Header
  )

  class ActivityOutput<R>(val activityId: String, val result: R?)

  class LocalActivityInput<R>(
    val activityName: String,
    val resultClass: Class<R>,
    val resultType: Type,
    val args: Array<out Any>,
    val options: LocalActivityOptions,
    val header: Header
  )

  class LocalActivityOutput<R>(val result: R?)
  class ChildWorkflowInput<R>(
    val workflowId: String,
    val workflowType: String,
    val resultClass: Class<R>,
    val resultType: Type,
    val args: Array<Any>,
    val options: ChildWorkflowOptions,
    val header: Header
  )

  class ChildWorkflowOutput<R>(val result: Promise<R>, val workflowExecution: Promise<WorkflowExecution>)
  class SignalExternalInput(val execution: WorkflowExecution, val signalName: String, val args: Array<Any>)
  class SignalExternalOutput(val result: Promise<Void>)
  class CancelWorkflowInput(val execution: WorkflowExecution)
  class CancelWorkflowOutput(val result: Promise<Void>)
  class ContinueAsNewInput(
    /**
     * @return workflowType for the continue-as-new workflow run. null if continue-as-new should
     * inherit the type of the original workflow run.
     */
    val workflowType: String?,
    /**
     * @return options for the continue-as-new workflow run. Can be null, in that case the values
     * will be taken from the original workflow run.
     */
    val options: ContinueAsNewOptions?,
    val args: Array<Any>,
    val header: Header
  )

  class SignalRegistrationRequest(
    val signalType: String,
    val argTypes: Array<Class<*>>,
    val genericArgTypes: Array<Type>,
    val callback: Proc1<Array<Any>>
  )

  class RegisterSignalHandlersInput(val requests: List<SignalRegistrationRequest>)

  @Experimental
  class UpdateRegistrationRequest(
    val updateName: String,
    val argTypes: Array<Class<*>>,
    val genericArgTypes: Array<Type>,
    val validateCallback: Proc1<Array<Any>>,
    val executeCallback: Func1<Array<Any>, Any>
  )

  @Experimental
  class RegisterUpdateHandlersInput(val requests: List<UpdateRegistrationRequest>)
  class RegisterQueryInput(
    val queryType: String,
    val argTypes: Array<Class<*>>,
    val genericArgTypes: Array<Type>,
    val callback: Func1<Array<Any>, Any>
  )

  class RegisterDynamicQueryHandlerInput(val handler: DynamicQueryHandler)
  class RegisterDynamicSignalHandlerInput(val handler: DynamicSignalHandler)

  @Experimental
  class RegisterDynamicUpdateHandlerInput(val handler: DynamicUpdateHandler)

  suspend fun <R> executeActivity(input: ActivityInput<R>): ActivityOutput<R?>
  suspend fun <R> executeLocalActivity(input: LocalActivityInput<R>): LocalActivityOutput<R?>
  suspend fun <R> executeChildWorkflow(input: ChildWorkflowInput<R>): ChildWorkflowOutput<R?>
  fun newRandom(): Random
  suspend fun signalExternalWorkflow(input: SignalExternalInput): SignalExternalOutput
  fun cancelWorkflow(input: CancelWorkflowInput): CancelWorkflowOutput

  // TODO: Consider removing sleep and keep only built in delay
  suspend fun sleep(duration: Duration)
  suspend fun await(timeout: Duration, reason: String?, unblockCondition: Supplier<Boolean?>): Boolean
  suspend fun await(reason: String?, unblockCondition: Supplier<Boolean?>)
  fun <R> sideEffect(resultClass: Class<R>, resultType: Type, func: Func<R?>): R?
  fun <R> mutableSideEffect(
    id: String,
    resultClass: Class<R>,
    resultType: Type,
    updated: BiPredicate<R?, R?>,
    func: Func<R?>
  ): R?

  fun getVersion(changeId: String, minSupported: Int, maxSupported: Int): Int
  fun continueAsNew(input: ContinueAsNewInput)
  fun registerQuery(input: RegisterQueryInput)
  fun registerSignalHandlers(input: RegisterSignalHandlersInput)

  @Experimental
  fun registerUpdateHandlers(input: RegisterUpdateHandlersInput)
  fun registerDynamicSignalHandler(input: RegisterDynamicSignalHandlerInput)
  fun registerDynamicQueryHandler(input: RegisterDynamicQueryHandlerInput)

  @Experimental
  fun registerDynamicUpdateHandler(input: RegisterDynamicUpdateHandlerInput)
  fun randomUUID(): UUID
  fun upsertSearchAttributes(searchAttributes: Map<String?, *>)
  fun upsertTypedSearchAttributes(searchAttributeUpdates: List<SearchAttributeUpdate<*>>)

  fun currentTimeMillis(): Long
}
