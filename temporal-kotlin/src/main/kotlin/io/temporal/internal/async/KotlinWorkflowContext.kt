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

import com.uber.m3.tally.Scope
import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes
import io.temporal.api.common.v1.ActivityType
import io.temporal.api.common.v1.Payload
import io.temporal.api.common.v1.Payloads
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.failure.v1.Failure
import io.temporal.api.taskqueue.v1.TaskQueue
import io.temporal.common.SearchAttributeUpdate
import io.temporal.common.context.ContextPropagator
import io.temporal.common.converter.DataConverter
import io.temporal.common.interceptors.Header
import io.temporal.failure.CanceledFailure
import io.temporal.internal.common.ActivityOptionUtils
import io.temporal.internal.common.HeaderUtils
import io.temporal.internal.common.ProtobufTimeUtils
import io.temporal.internal.common.SerializerUtils
import io.temporal.internal.replay.ReplayWorkflowContext
import io.temporal.internal.replay.WorkflowContext
import io.temporal.internal.statemachines.ExecuteActivityParameters
import io.temporal.kotlin.interceptors.WorkflowInboundCallsInterceptor
import io.temporal.kotlin.interceptors.WorkflowOutboundCallsInterceptor
import io.temporal.payload.context.ActivitySerializationContext
import io.temporal.payload.context.WorkflowSerializationContext
import io.temporal.worker.WorkflowImplementationOptions
import io.temporal.workflow.Functions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.lang.reflect.Type
import java.time.Duration
import java.util.*
import java.util.function.BiPredicate
import java.util.function.Supplier
import kotlin.coroutines.resumeWithException

class KotlinWorkflowContext(
  private val namespace: String,
  private val workflowExecution: WorkflowExecution,
  private var workflowImplementationOptions: WorkflowImplementationOptions?,
  val dataConverter: DataConverter,
  private val contextPropagators: List<ContextPropagator>?
) : WorkflowContext, WorkflowOutboundCallsInterceptor {

  private val log = LoggerFactory.getLogger(KotlinWorkflowContext::class.java)

  private var headInboundInterceptor: WorkflowInboundCallsInterceptor? = null
  private var headOutboundInterceptor: WorkflowOutboundCallsInterceptor? = null

  var defaultActivityOptions: ActivityOptions? = null
  var defaultLocalActivityOptions: LocalActivityOptions? = null

  private var activityOptionsMap: Map<String, ActivityOptions>? = null
  private var localActivityOptionsMap: Map<String, LocalActivityOptions>? = null

  private var replayContext: ReplayWorkflowContext? = null

  init {
    if (workflowImplementationOptions != null) {
      defaultActivityOptions = workflowImplementationOptions!!.defaultActivityOptions
      this.activityOptionsMap = HashMap<String, ActivityOptions>(workflowImplementationOptions!!.activityOptions)
      this.defaultLocalActivityOptions = workflowImplementationOptions!!.defaultLocalActivityOptions
      this.localActivityOptionsMap =
        HashMap<String, LocalActivityOptions>(workflowImplementationOptions!!.localActivityOptions)
      workflowImplementationOptions = WorkflowImplementationOptions.getDefaultInstance()
    }
    // initial values for headInboundInterceptor and headOutboundInterceptor until they initialized
    // with actual interceptors through #initHeadInboundCallsInterceptor and
    // #initHeadOutboundCallsInterceptor during initialization phase.
    // See workflow.initialize() performed inside the workflow root thread inside
    // SyncWorkflow#start(HistoryEvent, ReplayWorkflowContext)
    // initial values for headInboundInterceptor and headOutboundInterceptor until they initialized
    // with actual interceptors through #initHeadInboundCallsInterceptor and
    // #initHeadOutboundCallsInterceptor during initialization phase.
    // See workflow.initialize() performed inside the workflow root thread inside
    // SyncWorkflow#start(HistoryEvent, ReplayWorkflowContext)
    headInboundInterceptor = InitialWorkflowInboundCallsInterceptor(this)
    headOutboundInterceptor = this
  }

  private val dataConverterWithCurrentWorkflowContext = dataConverter.withContext(
    WorkflowSerializationContext(namespace, workflowExecution.getWorkflowId())
  )

  fun setReplayContext(context: ReplayWorkflowContext) {
    replayContext = context
  }

  override fun getReplayContext(): ReplayWorkflowContext? {
    return replayContext
  }

  fun getWorkflowOutboundInterceptor(): WorkflowOutboundCallsInterceptor {
    return headOutboundInterceptor!!
  }

  fun getWorkflowInboundInterceptor(): WorkflowInboundCallsInterceptor? {
    return headInboundInterceptor
  }

  fun initHeadOutboundCallsInterceptor(head: WorkflowOutboundCallsInterceptor) {
    headOutboundInterceptor = head
  }

  fun initHeadInboundCallsInterceptor(head: WorkflowInboundCallsInterceptor) {
    headInboundInterceptor = head
    // TODO: signal, query, update dispatchers
//    signalDispatcher.setInboundCallsInterceptor(head)
//    queryDispatcher.setInboundCallsInterceptor(head)
//    updateDispatcher.setInboundCallsInterceptor(head)
  }

  override fun mapWorkflowExceptionToFailure(failure: Throwable): Failure {
    return dataConverterWithCurrentWorkflowContext.exceptionToFailure(failure)
  }

  override fun getWorkflowImplementationOptions(): WorkflowImplementationOptions {
    return workflowImplementationOptions!!
  }

  fun applyActivityOptions(activityTypeToOption: Map<String, ActivityOptions>) {
    if (activityOptionsMap == null) {
      activityOptionsMap = HashMap(activityTypeToOption)
      return
    }
    ActivityOptionUtils.mergePredefinedActivityOptions(activityOptionsMap!!, activityTypeToOption)
  }

  fun applyLocalActivityOptions(activityTypeToOption: Map<String, LocalActivityOptions>) {
    if (localActivityOptionsMap == null) {
      localActivityOptionsMap = HashMap(activityTypeToOption)
      return
    }
    ActivityOptionUtils.mergePredefinedLocalActivityOptions(
      localActivityOptionsMap!!,
      activityTypeToOption
    )
  }

  override fun <R : Any?> getLastCompletionResult(resultClass: Class<R>, resultType: Type): R? {
    return dataConverter.fromPayloads(
      0,
      Optional.ofNullable(replayContext!!.lastCompletionResult),
      resultClass,
      resultType
    )
  }

  override fun getContextPropagators(): List<ContextPropagator>? {
    return contextPropagators
  }

  override fun getPropagatedContexts(): MutableMap<String, Any> {
    if (contextPropagators == null || contextPropagators.isEmpty()) {
      return HashMap()
    }

    val headerData: Map<String, Payload> = HashMap(replayContext!!.header)
    val contextData: MutableMap<String, Any> = HashMap()
    for (propagator in contextPropagators) {
      contextData[propagator.name] = propagator.deserializeContext(headerData)
    }

    return contextData
  }

  override suspend fun <R> executeActivity(input: WorkflowOutboundCallsInterceptor.ActivityInput<R>): WorkflowOutboundCallsInterceptor.ActivityOutput<R?> {
    val serializationContext = ActivitySerializationContext(
      replayContext!!.namespace,
      replayContext!!.workflowId,
      replayContext!!.workflowType.name,
      input.activityName,
      // input.getOptions().getTaskQueue() may be not specified, workflow task queue is used
      // by the Server in this case
      if (input.options.taskQueue != null) input.options.taskQueue else replayContext!!.taskQueue,
      false
    )
    val dataConverterWithActivityContext = dataConverter.withContext(serializationContext)
    val args = dataConverterWithActivityContext.toPayloads(*input.args)
    try {
      val output = executeActivityOnce(input.activityName, input.options, input.header, args)
      val result = if (input.resultType !== Void.TYPE) {
        dataConverterWithActivityContext.fromPayloads(
          0,
          Optional.of(output.result),
          input.resultClass,
          input.resultType
        )
      } else {
        null
      }
      return WorkflowOutboundCallsInterceptor.ActivityOutput(output.activityId, result)
    } catch (e: FailureWrapperException) {
      throw dataConverterWithActivityContext.failureToException(e.failure)
    }
  }

  override suspend fun <R> executeLocalActivity(input: WorkflowOutboundCallsInterceptor.LocalActivityInput<R>): WorkflowOutboundCallsInterceptor.LocalActivityOutput<R?> {
    TODO("Not yet implemented")
  }

  override suspend fun <R> executeChildWorkflow(input: WorkflowOutboundCallsInterceptor.ChildWorkflowInput<R>): WorkflowOutboundCallsInterceptor.ChildWorkflowOutput<R?> {
    TODO("Not yet implemented")
  }

  override fun newRandom(): Random = replayContext!!.newRandom()

  override suspend fun signalExternalWorkflow(input: WorkflowOutboundCallsInterceptor.SignalExternalInput): WorkflowOutboundCallsInterceptor.SignalExternalOutput {
    TODO("Not yet implemented")
  }

  override fun cancelWorkflow(input: WorkflowOutboundCallsInterceptor.CancelWorkflowInput): WorkflowOutboundCallsInterceptor.CancelWorkflowOutput {
    TODO("Not yet implemented")
  }

  override suspend fun await(timeout: Duration, reason: String?, unblockCondition: Supplier<Boolean?>): Boolean {
    TODO("Not yet implemented")
  }

  override suspend fun await(reason: String?, unblockCondition: Supplier<Boolean?>) {
    TODO("Not yet implemented")
  }

  override fun <R> sideEffect(resultClass: Class<R>, resultType: Type, func: Functions.Func<R?>): R? {
    TODO("Not yet implemented")
  }

  override fun <R> mutableSideEffect(
    id: String,
    resultClass: Class<R>,
    resultType: Type,
    updated: BiPredicate<R?, R?>,
    func: Functions.Func<R?>
  ): R? {
    TODO("Not yet implemented")
  }

  override fun getVersion(changeId: String, minSupported: Int, maxSupported: Int): Int {
    TODO("Not yet implemented")
  }

  override fun continueAsNew(input: WorkflowOutboundCallsInterceptor.ContinueAsNewInput) {
    TODO("Not yet implemented")
  }

  override fun registerQuery(input: WorkflowOutboundCallsInterceptor.RegisterQueryInput) {
    TODO("Not yet implemented")
  }

  override fun registerSignalHandlers(input: WorkflowOutboundCallsInterceptor.RegisterSignalHandlersInput) {
    TODO("Not yet implemented")
  }

  override fun registerUpdateHandlers(input: WorkflowOutboundCallsInterceptor.RegisterUpdateHandlersInput) {
    TODO("Not yet implemented")
  }

  override fun registerDynamicSignalHandler(input: WorkflowOutboundCallsInterceptor.RegisterDynamicSignalHandlerInput) {
    TODO("Not yet implemented")
  }

  override fun registerDynamicQueryHandler(input: WorkflowOutboundCallsInterceptor.RegisterDynamicQueryHandlerInput) {
    TODO("Not yet implemented")
  }

  override fun registerDynamicUpdateHandler(input: WorkflowOutboundCallsInterceptor.RegisterDynamicUpdateHandlerInput) {
    TODO("Not yet implemented")
  }

  override fun randomUUID(): UUID {
    TODO("Not yet implemented")
  }

  override fun upsertSearchAttributes(searchAttributes: Map<String?, *>) {
    TODO("Not yet implemented")
  }

  override fun upsertTypedSearchAttributes(searchAttributeUpdates: List<SearchAttributeUpdate<*>>) {
    TODO("Not yet implemented")
  }

  override fun currentTimeMillis(): Long {
    TODO("Not yet implemented")
  }

  val metricScope: Scope
    get() = replayContext!!.metricsScope

  @OptIn(ExperimentalCoroutinesApi::class)
  private suspend fun executeActivityOnce(
    activityTypeName: String,
    options: ActivityOptions,
    header: Header,
    input: Optional<Payloads>
  ): ActivityOutput<Payloads> {
    val params: ExecuteActivityParameters = constructExecuteActivityParameters(activityTypeName, options, header, input)

    return suspendCancellableCoroutine { continuation ->
      var activityId: String? = null
      val activityOutput = replayContext!!.scheduleActivityTask(
        params
      ) { output: Optional<Payloads>, failure: Failure? ->
        if (failure == null) {
          continuation.resume(ActivityOutput(activityId!!, output.get()), onCancellation = null)
        } else {
          continuation.resumeWithException(FailureWrapperException(failure))
        }
      }
      activityId = activityOutput.activityId
      // Handle coroutine cancellation
      continuation.invokeOnCancellation { reason: Throwable? ->
        activityOutput.cancellationHandle.apply(CanceledFailure(reason.toString()))
      }
    }
  }

  // TODO: this is copy of the similar method in SyncWorkflowContext. Extract to common class.
  private fun constructExecuteActivityParameters(
    name: String,
    options: ActivityOptions,
    header: Header,
    input: Optional<Payloads>
  ): ExecuteActivityParameters {
    var taskQueue = options.taskQueue
    if (taskQueue == null) {
      taskQueue = replayContext!!.taskQueue
    }
    val attributes = ScheduleActivityTaskCommandAttributes.newBuilder()
      .setActivityType(ActivityType.newBuilder().setName(name))
      .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue))
      .setScheduleToStartTimeout(
        ProtobufTimeUtils.toProtoDuration(options.scheduleToStartTimeout)
      )
      .setStartToCloseTimeout(
        ProtobufTimeUtils.toProtoDuration(options.startToCloseTimeout)
      )
      .setScheduleToCloseTimeout(
        ProtobufTimeUtils.toProtoDuration(options.scheduleToCloseTimeout)
      )
      .setHeartbeatTimeout(ProtobufTimeUtils.toProtoDuration(options.heartbeatTimeout))
      .setRequestEagerExecution(
        !options.isEagerExecutionDisabled && (taskQueue == replayContext!!.taskQueue)
      )
    input.ifPresent { value: Payloads? ->
      attributes.input = value
    }
    val retryOptions = options.retryOptions
    if (retryOptions != null) {
      attributes.setRetryPolicy(SerializerUtils.toRetryPolicy(retryOptions))
    }

    // Set the context value.  Use the context propagators from the ActivityOptions
    // if present, otherwise use the ones configured on the WorkflowContext
    var propagators = options.contextPropagators
    if (propagators == null) {
      propagators = contextPropagators
    }
    val grpcHeader = HeaderUtils.toHeaderGrpc(header, extractContextsAndConvertToBytes(propagators))
    attributes.header = grpcHeader
    if (options.versioningIntent != null) {
      attributes.useCompatibleVersion = options
        .versioningIntent
        .determineUseCompatibleFlag(replayContext!!.taskQueue == options.taskQueue)
    }
    return ExecuteActivityParameters(attributes, options.cancellationType)
  }

  // TODO: this is copy of the similar method in SyncWorkflowContext. Extract to common class.
  private fun extractContextsAndConvertToBytes(
    contextPropagators: List<ContextPropagator>?
  ): Header? {
    if (contextPropagators == null) {
      return null
    }
    val result: MutableMap<String, Payload> = HashMap()
    for (propagator in contextPropagators) {
      result.putAll(propagator.serializeContext(propagator.currentContext))
    }
    return Header(result)
  }

  // TODO: this is copy of the similar method in SyncWorkflowContext. Extract to common class.
  private class FailureWrapperException(val failure: Failure) :
    RuntimeException()

  class ActivityOutput<R>(val activityId: String, val result: R)

  /**
   * This WorkflowInboundCallsInterceptor is used during creation of the initial root workflow
   * thread and should be replaced with another specific implementation during initialization stage
   * `workflow.initialize()` performed inside the workflow root thread.
   *
   * @see KotlinWorkflow.start
   */
  private class InitialWorkflowInboundCallsInterceptor(workflowContext: KotlinWorkflowContext) :
    BaseRootKotlinWorkflowInboundCallsInterceptor(workflowContext) {
    override suspend fun execute(input: WorkflowInboundCallsInterceptor.WorkflowInput): WorkflowInboundCallsInterceptor.WorkflowOutput {
      throw UnsupportedOperationException(
        "SyncWorkflowContext should be initialized with a non-initial WorkflowInboundCallsInterceptor " +
          "before #execute can be called"
      )
    }
  }
}
