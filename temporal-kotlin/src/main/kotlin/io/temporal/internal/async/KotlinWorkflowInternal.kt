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
import io.temporal.api.failure.v1.Failure
import io.temporal.common.converter.DataConverter
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata
import io.temporal.workflow.KotlinWorkflowInfo
import io.temporal.workflow.WorkflowMethod
import kotlinx.coroutines.currentCoroutineContext
import java.util.*

/**
 * Never reference directly. It is public only because Java doesn't have internal package support.
 */
class KotlinWorkflowInternal {

  companion object {
    const val DEFAULT_VERSION = -1

    /**
     * Register query or queries implementation object. There is no need to register top level
     * workflow implementation object as it is done implicitly. Only methods annotated with @[ ] are registered. TODO(quinn) LIES!
     */
    fun registerListener(implementation: Any) {
      TODO("Implement")
//        if (implementation is DynamicSignalHandler) {
//            workflowOutboundInterceptor
//                .registerDynamicSignalHandler(
//                    WorkflowOutboundCallsInterceptor.RegisterDynamicSignalHandlerInput(
//                        implementation
//                    )
//                )
//            return
//        }
//        if (implementation is DynamicQueryHandler) {
//            workflowOutboundInterceptor
//                .registerDynamicQueryHandler(
//                    WorkflowOutboundCallsInterceptor.RegisterDynamicQueryHandlerInput(
//                        implementation
//                    )
//                )
//            return
//        }
//        if (implementation is DynamicUpdateHandler) {
//            workflowOutboundInterceptor
//                .registerDynamicUpdateHandler(
//                    WorkflowOutboundCallsInterceptor.RegisterDynamicUpdateHandlerInput(
//                        implementation
//                    )
//                )
//            return
//        }
//        val cls: Class<*> = implementation.javaClass
//        val workflowMetadata = POJOWorkflowImplMetadata.newListenerInstance(cls)
//        for (methodMetadata in workflowMetadata.queryMethods) {
//            val method = methodMetadata.workflowMethod
//            workflowOutboundInterceptor
//                .registerQuery(
//                    WorkflowOutboundCallsInterceptor.RegisterQueryInput(
//                        methodMetadata.name,
//                        method.parameterTypes,
//                        method.genericParameterTypes, label@
//                        Func1 { args: Array<Any?> ->
//                            try {
//                                return@label method.invoke(implementation, *args)
//                            } catch (e: Throwable) {
//                                throw CheckedExceptionWrapper.wrap(e)
//                            }
//                        })
//                )
//        }
//        val requests: MutableList<WorkflowOutboundCallsInterceptor.SignalRegistrationRequest> = ArrayList()
//        for (methodMetadata in workflowMetadata.signalMethods) {
//            val method = methodMetadata.workflowMethod
//            requests.add(
//                WorkflowOutboundCallsInterceptor.SignalRegistrationRequest(
//                    methodMetadata.name,
//                    method.parameterTypes,
//                    method.genericParameterTypes
//                ) { args: Array<Any?> ->
//                    try {
//                        method.invoke(implementation, *args)
//                    } catch (e: Throwable) {
//                        throw CheckedExceptionWrapper.wrap(e)
//                    }
//                })
//        }
//        if (!requests.isEmpty()) {
//            workflowOutboundInterceptor
//                .registerSignalHandlers(
//                    WorkflowOutboundCallsInterceptor.RegisterSignalHandlersInput(requests)
//                )
//        }
//
//        // Get all validators and lazily assign them to update handlers as we see them.
//        val validators: MutableMap<String, POJOWorkflowMethodMetadata> =
//            HashMap(workflowMetadata.updateValidatorMethods.size)
//        for (methodMetadata in workflowMetadata.updateValidatorMethods) {
//            val method = methodMetadata.workflowMethod
//            val updateValidatorMethod = method.getAnnotation(
//                UpdateValidatorMethod::class.java
//            )
//            require(!validators.containsKey(updateValidatorMethod.updateName())) { "Duplicate validator for update handle " + updateValidatorMethod.updateName() }
//            validators[updateValidatorMethod.updateName()] = methodMetadata
//        }
//        val updateRequests: MutableList<WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest> = ArrayList()
//        for (methodMetadata in workflowMetadata.updateMethods) {
//            val method = methodMetadata.workflowMethod
//            val updateMethod = method.getAnnotation(UpdateMethod::class.java)
//            var updateMethodName: String = updateMethod.name()
//            if (updateMethodName.isEmpty()) {
//                updateMethodName = method.name
//            }
//            // Check if any validators claim they are the validator for this update
//            val validatorMethodMetadata = validators.remove(updateMethodName)
//            var validatorMethod: Method?
//            if (validatorMethodMetadata != null) {
//                validatorMethod = validatorMethodMetadata.workflowMethod
//                require(Arrays.equals(validatorMethod.parameterTypes, method.parameterTypes)) {
//                    ("Validator for: "
//                            + updateMethodName
//                            + " type parameters do not match the update handle")
//                }
//            } else {
//                validatorMethod = null
//            }
//            updateRequests.add(
//                WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest(
//                    methodMetadata.name,
//                    method.parameterTypes,
//                    method.genericParameterTypes,
//                    { args: Array<Any?> ->
//                        try {
//                            validatorMethod?.invoke?.invoke()
//                        } catch (e: Throwable) {
//                            throw CheckedExceptionWrapper.wrap(e)
//                        }
//                    }, label@
//                    Func1<Array<Any?>, Any> { args: Array<Any?> ->
//                        try {
//                            return@label method.invoke(implementation, *args)
//                        } catch (e: Throwable) {
//                            throw CheckedExceptionWrapper.wrap(e)
//                        }
//                    })
//            )
//        }
//        if (!updateRequests.isEmpty()) {
//            workflowOutboundInterceptor
//                .registerUpdateHandlers(
//                    WorkflowOutboundCallsInterceptor.RegisterUpdateHandlersInput(updateRequests)
//                )
//        }
//        require(validators.isEmpty()) {
//            ("Missing update methods for update validator(s): "
//                    + Joiner.on(", ").join(validators.keys))
//        }
    }

    /** Should be used to get current time instead of [System.currentTimeMillis]  */
    suspend fun currentTimeMillis(): Long {
      return getWorkflowOutboundCallsInterceptor().currentTimeMillis()
    }

    suspend fun setDefaultActivityOptions(activityOptions: ActivityOptions?) {
      getRootWorkflowContext().defaultActivityOptions = activityOptions
    }

    suspend fun applyActivityOptions(activityTypeToOptions: Map<String, ActivityOptions>) {
      getRootWorkflowContext().applyActivityOptions(activityTypeToOptions)
    }

    suspend fun setDefaultLocalActivityOptions(localActivityOptions: LocalActivityOptions?) {
      getRootWorkflowContext().defaultLocalActivityOptions = localActivityOptions
    }

    suspend fun applyLocalActivityOptions(
      activityTypeToOptions: Map<String, LocalActivityOptions>
    ) {
      getRootWorkflowContext().applyLocalActivityOptions(activityTypeToOptions)
    }

//  /**
//   * Creates client stub to activities that implement given interface.
//   *
//   * @param activityInterface interface type implemented by activities
//   * @param options options that together with the properties of [     ] specify the activity invocation parameters
//   * @param activityMethodOptions activity method-specific invocation parameters
//   */
// //  fun <T> newActivityStub(
// //    activityInterface: Class<T>?,
// //    options: ActivityOptions?,
// //    activityMethodOptions: Map<String?, ActivityOptions?>?
// //  ): T {
// //    // Merge the activity options we may have received from the workflow with the options we may
// //    // have received in WorkflowImplementationOptions.
// //    var options = options
// //    val context = rootWorkflowContext
// //    options = options ?: context.getDefaultActivityOptions()
// //    val mergedActivityOptionsMap: Map<String?, ActivityOptions?>
// //    @Nonnull val predefinedActivityOptions = context.getActivityOptions()
// //    if (activityMethodOptions != null && !activityMethodOptions.isEmpty()
// //      && predefinedActivityOptions.isEmpty()
// //    ) {
// //      // we need to merge only in this case
// //      mergedActivityOptionsMap = HashMap(predefinedActivityOptions)
// //      ActivityOptionUtils.mergePredefinedActivityOptions(
// //        mergedActivityOptionsMap, activityMethodOptions
// //      )
// //    } else {
// //      mergedActivityOptionsMap = MoreObjects.firstNonNull(
// //        activityMethodOptions,
// //        MoreObjects.firstNonNull(predefinedActivityOptions, emptyMap<String?, ActivityOptions>())
// //      )
// //    }
// //    val invocationHandler = ActivityInvocationHandler.newInstance(
// //      activityInterface,
// //      options,
// //      mergedActivityOptionsMap,
// //      context.getWorkflowOutboundInterceptor()
// //    )
// //    return ActivityInvocationHandlerBase.newProxy(activityInterface, invocationHandler)
// //  }
// //
//  /**
//   * Creates client stub to local activities that implement given interface.
//   *
//   * @param activityInterface interface type implemented by activities
//   * @param options options that together with the properties of [     ] specify the activity invocation parameters
//   * @param activityMethodOptions activity method-specific invocation parameters
//   */
//  fun <T> newLocalActivityStub(
//    activityInterface: Class<T>?,
//    options: LocalActivityOptions?,
//    activityMethodOptions: Map<String?, LocalActivityOptions?>?
//  ): T {
//    // Merge the activity options we may have received from the workflow with the options we may
//    // have received in WorkflowImplementationOptions.
//    var options = options
//    val context = rootWorkflowContext
//    options = options ?: context.getDefaultLocalActivityOptions()
//    val mergedLocalActivityOptionsMap: Map<String?, LocalActivityOptions?>
//    @Nonnull val predefinedLocalActivityOptions = context.getLocalActivityOptions()
//    if (activityMethodOptions != null && !activityMethodOptions.isEmpty()
//      && predefinedLocalActivityOptions.isEmpty()
//    ) {
//      // we need to merge only in this case
//      mergedLocalActivityOptionsMap = HashMap(predefinedLocalActivityOptions)
//      ActivityOptionUtils.mergePredefinedLocalActivityOptions(
//        mergedLocalActivityOptionsMap, activityMethodOptions
//      )
//    } else {
//      mergedLocalActivityOptionsMap = MoreObjects.firstNonNull(
//        activityMethodOptions,
//        MoreObjects.firstNonNull(predefinedLocalActivityOptions, emptyMap<String?, LocalActivityOptions>())
//      )
//    }
//    val invocationHandler = LocalActivityInvocationHandler.newInstance(
//      activityInterface,
//      options,
//      mergedLocalActivityOptionsMap,
//      workflowOutboundInterceptor
//    )
//    return ActivityInvocationHandlerBase.newProxy(activityInterface, invocationHandler)
//  }

    suspend fun newUntypedActivityStub(options: ActivityOptions?): KotlinActivityStub {
      return KotlinActivityStubImpl(options, getWorkflowOutboundCallsInterceptor())
    }

//  fun newUntypedLocalActivityStub(options: LocalActivityOptions?): KotlinActivityStub {
//    return LocalActivityStubImpl.newInstance(options, workflowOutboundInterceptor)
//  }
//
//  fun <T> newChildWorkflowStub(
//    workflowInterface: Class<T>, options: ChildWorkflowOptions?
//  ): T {
//    return Proxy.newProxyInstance(
//      workflowInterface.classLoader, arrayOf(workflowInterface, StubMarker::class.java, AsyncMarker::class.java),
//      ChildWorkflowInvocationHandler(
//        workflowInterface, options, workflowOutboundInterceptor
//      )
//    ) as T
//  }
//
//  fun <T> newExternalWorkflowStub(
//    workflowInterface: Class<T>, execution: WorkflowExecution?
//  ): T {
//    return Proxy.newProxyInstance(
//      workflowInterface.classLoader, arrayOf(workflowInterface, StubMarker::class.java, AsyncMarker::class.java),
//      ExternalWorkflowInvocationHandler(
//        workflowInterface, execution, workflowOutboundInterceptor
//      )
//    ) as T
//  }
//
//  fun getWorkflowExecution(workflowStub: Any): Promise<WorkflowExecution> {
//    if (workflowStub is StubMarker) {
//      val stub = workflowStub.__getUntypedStub()
//      return (stub as ChildWorkflowStub).execution
//    }
//    throw IllegalArgumentException(
//      "Not a workflow stub created through Workflow.newChildWorkflowStub: $workflowStub"
//    )
//  }
//
//  fun newUntypedChildWorkflowStub(
//    workflowType: String?, options: ChildWorkflowOptions?
//  ): ChildWorkflowStub {
//    return ChildWorkflowStubImpl(workflowType, options, workflowOutboundInterceptor)
//  }
//
//  fun newUntypedExternalWorkflowStub(execution: WorkflowExecution?): ExternalWorkflowStub {
//    return ExternalWorkflowStubImpl(execution, workflowOutboundInterceptor)
//  }
//
//  /**
//   * Creates client stub that can be used to continue this workflow as new.
//   *
//   * @param workflowInterface interface type implemented by the next generation of workflow
//   */
//  fun <T> newContinueAsNewStub(
//    workflowInterface: Class<T>, options: ContinueAsNewOptions?
//  ): T {
//    return Proxy.newProxyInstance(
//      workflowInterface.classLoader, arrayOf<Class<*>>(workflowInterface),
//      ContinueAsNewWorkflowInvocationHandler(
//        workflowInterface, options, workflowOutboundInterceptor
//      )
//    ) as T
//  }
//
//  /**
//   * Execute activity by name.
//   *
//   * @param name name of the activity
//   * @param resultClass activity return type
//   * @param args list of activity arguments
//   * @param <R> activity return type
//   * @return activity result
//  </R> */
//  fun <R> executeActivity(
//    name: String?, options: ActivityOptions?, resultClass: Class<R>?, resultType: Type?, vararg args: Any?
//  ): R? {
//    val result = workflowOutboundInterceptor
//      .executeActivity(
//        WorkflowOutboundCallsInterceptor.ActivityInput(
//          name, resultClass, resultType, args, options, Header.empty()
//        )
//      )
//      .result
//    if (AsyncInternal.isAsync()) {
//      AsyncInternal.setAsyncResult(result)
//      return null // ignored
//    }
//    return result.get()
//  }
//
//  @Throws(DestroyWorkflowThreadError::class)
//  fun await(reason: String?, unblockCondition: Supplier<Boolean?>?) {
//    workflowOutboundInterceptor.await(reason, unblockCondition)
//  }
//
//  @Throws(DestroyWorkflowThreadError::class)
//  fun await(timeout: Duration?, reason: String?, unblockCondition: Supplier<Boolean?>?): Boolean {
//    return workflowOutboundInterceptor.await(timeout, reason, unblockCondition)
//  }
//
//  fun <R> sideEffect(resultClass: Class<R>?, resultType: Type?, func: Func<R>?): R {
//    return workflowOutboundInterceptor.sideEffect(resultClass, resultType, func)
//  }
//
//  fun <R> mutableSideEffect(
//    id: String?, resultClass: Class<R>?, resultType: Type?, updated: BiPredicate<R, R>?, func: Func<R>?
//  ): R {
//    return workflowOutboundInterceptor
//      .mutableSideEffect(id, resultClass, resultType, updated, func)
//  }
//
//  fun getVersion(changeId: String?, minSupported: Int, maxSupported: Int): Int {
//    return workflowOutboundInterceptor.getVersion(changeId, minSupported, maxSupported)
//  }
//
//  fun <V> promiseAllOf(promises: Iterable<Promise<V>?>?): Promise<Void> {
//    return AllOfPromise(promises)
//  }
//
//  fun promiseAllOf(vararg promises: Promise<*>?): Promise<Void> {
//    return AllOfPromise(promises)
//  }
//
//  fun <V> promiseAnyOf(promises: Iterable<Promise<V>?>?): Promise<V> {
//    return CompletablePromiseImpl.promiseAnyOf(promises)
//  }
//
//  fun promiseAnyOf(vararg promises: Promise<*>?): Promise<Any> {
//    return CompletablePromiseImpl.promiseAnyOf(promises)
//  }
//
//  fun newCancellationScope(detached: Boolean, runnable: Runnable?): CancellationScope {
//    return CancellationScopeImpl(detached, runnable)
//  }
//
//  fun newCancellationScope(
//    detached: Boolean, proc: Proc1<CancellationScope?>?
//  ): CancellationScope {
//    return CancellationScopeImpl(detached, proc)
//  }
//
//  fun currentCancellationScope(): CancellationScopeImpl {
//    return CancellationScopeImpl.current()
//  }
//
//  fun wrap(e: Throwable?): RuntimeException {
//    return CheckedExceptionWrapper.wrap(e)
//  }
//
//  fun unwrap(e: Throwable?): Throwable {
//    return CheckedExceptionWrapper.unwrap(e)
//  }
//
//  /** Returns false if not under workflow code.  */
//  val isReplaying: Boolean
//    get() {
//      val thread = DeterministicRunnerImpl.currentThreadInternalIfPresent()
//      return thread.isPresent && getRootWorkflowContext().isReplaying()
//    }
//
//  fun <T> getMemo(key: String?, valueClass: Class<T>?, genericType: Type?): T? {
//    val memo = getRootWorkflowContext().getReplayContext().getMemo(key) ?: return null
//    return dataConverter.fromPayload(memo, valueClass, genericType)
//  }
//
//  fun <R> retry(
//    options: RetryOptions, expiration: Optional<Duration?>?, fn: Func<R>?
//  ): R {
//    return WorkflowRetryerInternal.retry(
//      options.toBuilder().validateBuildWithDefaults(), expiration, fn
//    )
//  }
//
//  fun continueAsNew(
//    workflowType: String?, options: ContinueAsNewOptions?, args: Array<Any?>?
//  ) {
//    workflowOutboundInterceptor
//      .continueAsNew(
//        WorkflowOutboundCallsInterceptor.ContinueAsNewInput(
//          workflowType, options, args, Header.empty()
//        )
//      )
//  }
//
//  fun continueAsNew(
//    workflowType: String?,
//    options: ContinueAsNewOptions?,
//    args: Array<Any?>?,
//    outboundCallsInterceptor: WorkflowOutboundCallsInterceptor
//  ) {
//    outboundCallsInterceptor.continueAsNew(
//      WorkflowOutboundCallsInterceptor.ContinueAsNewInput(
//        workflowType, options, args, Header.empty()
//      )
//    )
//  }
//
//  fun cancelWorkflow(execution: WorkflowExecution?): Promise<Void> {
//    return workflowOutboundInterceptor
//      .cancelWorkflow(WorkflowOutboundCallsInterceptor.CancelWorkflowInput(execution))
//      .result
//  }
//
//  fun sleep(duration: Duration?) {
//    workflowOutboundInterceptor.sleep(duration)
//  }
//
//  val isWorkflowThread: Boolean
//    get() = WorkflowThreadMarker.isWorkflowThread()
//
//  fun <T> deadlockDetectorOff(func: Func<T>): T {
//    if (isWorkflowThread) {
//      getWorkflowThread().lockDeadlockDetector().use { ignored -> return func.apply() }
//    } else {
//      return func.apply()
//    }
//  }

    suspend fun getWorkflowInfo(): KotlinWorkflowInfo = KotlinWorkflowInfoImpl(getRootWorkflowContext().replayContext!!)

    suspend fun getMetricsScope(): Scope = getRootWorkflowContext().metricScope

    suspend fun randomUUID(): UUID = getRootWorkflowContext().randomUUID()

    suspend fun newRandom(): Random = getRootWorkflowContext().newRandom()

//  private val isLoggingEnabledInReplay: Boolean
//    private get() = getRootWorkflowContext().isLoggingEnabledInReplay()
//  fun getLogger(clazz: Class<*>?): Logger {
//    val logger = LoggerFactory.getLogger(clazz)
//    return ReplayAwareLogger(
//      logger,
//      ReplayAware { obj: KotlinWorkflowInternal? -> isReplaying },
//      Supplier { obj: KotlinWorkflowInternal? -> isLoggingEnabledInReplay })
//  }
//
//  fun getLogger(name: String?): Logger {
//    val logger = LoggerFactory.getLogger(name)
//    return ReplayAwareLogger(
//      logger,
//      ReplayAware { obj: KotlinWorkflowInternal? -> isReplaying },
//      Supplier { obj: KotlinWorkflowInternal? -> isLoggingEnabledInReplay })
//  }

//  fun <R> getLastCompletionResult(resultClass: Class<R>?, resultType: Type?): R? {
//    return getRootWorkflowContext().getLastCompletionResult(resultClass, resultType)
//  }
//
//  fun <T> getSearchAttribute(name: String?): T? {
//    val list = getSearchAttributeValues<T>(name) ?: return null
//    Preconditions.checkState(list.size > 0)
//    Preconditions.checkState(
//      list.size == 1,
//      "search attribute with name '%s' contains a list '%s' of values instead of a single value",
//      name,
//      list
//    )
//    return list[0]
//  }
//
//  fun <T> getSearchAttributeValues(name: String?): List<T>? {
//    val searchAttributes = getRootWorkflowContext().getReplayContext().searchAttributes ?: return null
//    val decoded = SearchAttributesUtil.decode<T>(searchAttributes, name!!)
//    return if (decoded != null) Collections.unmodifiableList(decoded) else null
//  }
//
//  val searchAttributes: Map<String, List<*>>
//    get() {
//      val searchAttributes = getRootWorkflowContext().getReplayContext().searchAttributes
//        ?: return emptyMap()
//      return Collections.unmodifiableMap(SearchAttributesUtil.decode(searchAttributes))
//    }
//
//  val typedSearchAttributes: SearchAttributes
//    get() {
//      val searchAttributes = getRootWorkflowContext().getReplayContext().searchAttributes
//      return SearchAttributesUtil.decodeTyped(searchAttributes)
//    }
//
//  fun upsertSearchAttributes(searchAttributes: Map<String?, *>?) {
//    workflowOutboundInterceptor.upsertSearchAttributes(searchAttributes)
//  }
//
//  fun upsertTypedSearchAttributes(
//    vararg searchAttributeUpdates: SearchAttributeUpdate<*>?
//  ) {
//    workflowOutboundInterceptor.upsertTypedSearchAttributes(*searchAttributeUpdates)
//  }

    suspend fun getDataConverter(): DataConverter = getRootWorkflowContext().dataConverter

    /**
     * Name of the workflow type the interface defines. It is either the interface short name * or
     * value of [WorkflowMethod.name] parameter.
     *
     * @param workflowInterfaceClass interface annotated with @WorkflowInterface
     */
    fun getWorkflowType(workflowInterfaceClass: Class<*>?): String {
      val metadata = POJOWorkflowInterfaceMetadata.newInstance(workflowInterfaceClass)
      return metadata.workflowType.get()
    }

    // Temporal Failure Values are additional user payload and serialized using user data
    // converter
    suspend fun getPreviousRunFailure(): Optional<Exception> {
      // Temporal Failure Values are additional user payload and serialized using user data converter
      val dataConverter = getDataConverter()
      return Optional.ofNullable(getRootWorkflowContext().replayContext!!.previousRunFailure)
        .map { f: Failure? ->
          dataConverter.failureToException(f!!)
        }
    }

    suspend fun getWorkflowOutboundCallsInterceptor() = getRootWorkflowContext().getWorkflowOutboundInterceptor()

    suspend fun getRootWorkflowContext(): KotlinWorkflowContext {
      val temporalCoroutineContext = currentCoroutineContext()[TemporalCoroutineContext]
      if (temporalCoroutineContext == null) {
        throw Error("Called from non workflow thread or coroutine")
      }
      return temporalCoroutineContext.workflowContext
    }
  }
}
