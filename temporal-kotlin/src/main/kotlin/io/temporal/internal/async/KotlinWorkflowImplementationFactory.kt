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

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableSet
import io.temporal.api.common.v1.Payloads
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.common.v1.WorkflowType
import io.temporal.common.context.ContextPropagator
import io.temporal.common.converter.DataConverter
import io.temporal.common.interceptors.Header
import io.temporal.common.metadata.POJOWorkflowImplMetadata
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata
import io.temporal.common.metadata.WorkflowMethodType
import io.temporal.failure.CanceledFailure
import io.temporal.internal.common.env.ReflectionUtils
import io.temporal.internal.replay.ReplayWorkflow
import io.temporal.internal.replay.ReplayWorkflowFactory
import io.temporal.internal.sync.*
import io.temporal.internal.worker.SingleWorkerOptions
import io.temporal.internal.worker.WorkflowExecutionException
import io.temporal.internal.worker.WorkflowExecutorCache
import io.temporal.kotlin.interceptors.WorkerInterceptor
import io.temporal.kotlin.interceptors.WorkflowInboundCallsInterceptor
import io.temporal.kotlin.interceptors.WorkflowOutboundCallsInterceptor
import io.temporal.kotlin.workflow.KotlinDynamicWorkflow
import io.temporal.payload.context.WorkflowSerializationContext
import io.temporal.serviceclient.CheckedExceptionWrapper
import io.temporal.worker.TypeAlreadyRegisteredException
import io.temporal.worker.WorkflowImplementationOptions
import io.temporal.workflow.DynamicWorkflow
import io.temporal.workflow.Functions.Func
import io.temporal.workflow.Functions.Func1
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*

class KotlinWorkflowImplementationFactory(
  singleWorkerOptions: SingleWorkerOptions,
  workerInterceptors: Array<WorkerInterceptor>,
  cache: WorkflowExecutorCache,
  namespace: String
) : ReplayWorkflowFactory {
  private val workerInterceptors: Array<WorkerInterceptor>
  private val dataConverter: DataConverter
  private val contextPropagators: List<ContextPropagator>
  private val defaultDeadlockDetectionTimeout: Long

  /** Key: workflow type name, Value: function that creates SyncWorkflowDefinition instance.  */
  private val workflowDefinitions =
    Collections.synchronizedMap(HashMap<String, Func1<WorkflowExecution, KotlinWorkflowDefinition>>())

  /** Factories providing instances of workflow classes.  */
  private val workflowInstanceFactories = Collections.synchronizedMap(HashMap<Class<*>, Func<*>>())

  /** If present then it is called for any unknown workflow type.  */
  private var dynamicWorkflowImplementationFactory: Func<out KotlinDynamicWorkflow>? = null
  private val implementationOptions = Collections.synchronizedMap(HashMap<String, WorkflowImplementationOptions>())
  private val cache: WorkflowExecutorCache
  private val namespace: String

  init {
    Objects.requireNonNull(singleWorkerOptions)
    dataConverter = singleWorkerOptions.dataConverter
    this.workerInterceptors = Objects.requireNonNull(workerInterceptors)
    this.cache = cache
    contextPropagators = singleWorkerOptions.contextPropagators
    defaultDeadlockDetectionTimeout = singleWorkerOptions.defaultDeadlockDetectionTimeout
    this.namespace = namespace
  }

  fun registerWorkflowImplementationTypes(
    options: WorkflowImplementationOptions, workflowImplementationTypes: Array<Class<*>>
  ) {
    for (type in workflowImplementationTypes) {
      registerWorkflowImplementationType(options, type)
    }
  }

  /**
   * @param clazz has to be a workflow interface class. The only exception is if it's a
   * DynamicWorkflow class.
   */
  fun <R> addWorkflowImplementationFactory(
    options: WorkflowImplementationOptions, clazz: Class<R>, factory: Func<R?>
  ) {
    if (DynamicWorkflow::class.java.isAssignableFrom(clazz)) {
      if (dynamicWorkflowImplementationFactory != null) {
        throw TypeAlreadyRegisteredException(
          "KotlinDynamicWorkflow",
          "An implementation of KotlinDynamicWorkflow or its factory is already registered with the worker"
        )
      }
      dynamicWorkflowImplementationFactory = factory as Func<out KotlinDynamicWorkflow>
      return
    }
    workflowInstanceFactories[clazz] = factory
    val workflowMetadata = POJOWorkflowInterfaceMetadata.newInstance(clazz)
    require(workflowMetadata.workflowMethod.isPresent) { "Workflow interface doesn't contain a method annotated with @WorkflowMethod: $clazz" }
    val methodsMetadata = workflowMetadata.methodsMetadata
    for (methodMetadata in methodsMetadata) {
      when (methodMetadata.type) {
        WorkflowMethodType.WORKFLOW -> {
          val typeName = methodMetadata.name
          if (workflowDefinitions.containsKey(typeName)) {
            throw TypeAlreadyRegisteredException(
              typeName,
              "\"$typeName\" workflow type is already registered with the worker"
            )
          }
          workflowDefinitions[typeName] = Func1 { execution: WorkflowExecution ->
            KotlinWorkflowImplementation(
              clazz,
              methodMetadata.workflowMethod,
              dataConverter.withContext(
                WorkflowSerializationContext(namespace, execution.workflowId)
              )
            )
          }
          implementationOptions[typeName] = options
        }
        WorkflowMethodType.SIGNAL -> {}
      }
    }
  }

  private fun <T> registerWorkflowImplementationType(
    options: WorkflowImplementationOptions, workflowImplementationClass: Class<T>
  ) {
//    if (KotlinDynamicWorkflow::class.java.isAssignableFrom(workflowImplementationClass)) {
//      addWorkflowImplementationFactory<T>(
//        options,
//        workflowImplementationClass,
//        Func<T> {
//          try {
//            val newInstance: T? = workflowImplementationClass.getDeclaredConstructor().newInstance()
//            return@Func newInstance
//          } catch (e: NoSuchMethodException) {
//            // Error to fail workflow task as this can be fixed by a new deployment.
//            throw Error(
//              "Failure instantiating workflow implementation class "
//                + workflowImplementationClass.name,
//              e
//            )
//          } catch (e: InstantiationException) {
//            throw Error(
//              "Failure instantiating workflow implementation class "
//                + workflowImplementationClass.name,
//              e
//            )
//          } catch (e: IllegalAccessException) {
//            throw Error(
//              "Failure instantiating workflow implementation class "
//                + workflowImplementationClass.name,
//              e
//            )
//          } catch (e: InvocationTargetException) {
//            throw Error(
//              "Failure instantiating workflow implementation class "
//                + workflowImplementationClass.name,
//              e
//            )
//          }
//        })
//      return
//    }
    val workflowMetadata = POJOWorkflowImplMetadata.newInstance(workflowImplementationClass)
    val workflowMethods = workflowMetadata.workflowMethods
    require(!workflowMethods.isEmpty()) {
      ("Workflow implementation doesn't implement any interface "
        + "with a workflow method annotated with @WorkflowMethod: "
        + workflowImplementationClass)
    }
    for (workflowMethod in workflowMethods) {
      val workflowName = workflowMethod.name
      val method = workflowMethod.workflowMethod
      val definition = Func1<WorkflowExecution, KotlinWorkflowDefinition> { execution: WorkflowExecution ->
        KotlinWorkflowImplementation(
          workflowImplementationClass,
          method,
          dataConverter.withContext(
            WorkflowSerializationContext(namespace, execution.workflowId)
          )
        )
      }
      check(!workflowDefinitions.containsKey(workflowName)) { "$workflowName workflow type is already registered with the worker" }
      workflowDefinitions[workflowName] = definition
      implementationOptions[workflowName] = options
    }
  }

  private fun getWorkflowDefinition(
    workflowType: WorkflowType, workflowExecution: WorkflowExecution
  ): KotlinWorkflowDefinition {
    val factory = workflowDefinitions[workflowType.name]
    if (factory == null) {
      if (dynamicWorkflowImplementationFactory != null) {
        return DynamicKotlinWorkflowDefinition(
          dynamicWorkflowImplementationFactory!!, workerInterceptors, dataConverter
        )
      }
      throw Error(
        "Unknown workflow type \""
          + workflowType.name
          + "\". Known types are "
          + workflowDefinitions.keys
      )
    }
    return try {
      factory.apply(workflowExecution)
    } catch (e: Exception) {
      throw Error(e)
    }
  }

  override fun getWorkflow(
    workflowType: WorkflowType, workflowExecution: WorkflowExecution
  ): ReplayWorkflow {
    val workflow = getWorkflowDefinition(workflowType, workflowExecution)
    val workflowImplementationOptions = implementationOptions[workflowType.name]
    val dataConverterWithWorkflowContext = dataConverter.withContext(
      WorkflowSerializationContext(namespace, workflowExecution.workflowId)
    )
    return KotlinWorkflow(
      namespace,
      workflowExecution,
      workflow,
//      SignalDispatcher(dataConverterWithWorkflowContext),
//      QueryDispatcher(dataConverterWithWorkflowContext),
//      UpdateDispatcher(dataConverterWithWorkflowContext),
      workflowImplementationOptions,
      dataConverter,
      cache,
      contextPropagators,
      defaultDeadlockDetectionTimeout
    )
  }

  override fun isAnyTypeSupported(): Boolean {
    return !workflowDefinitions.isEmpty() || dynamicWorkflowImplementationFactory != null
  }

  private inner class KotlinWorkflowImplementation(
    private val workflowImplementationClass: Class<*>,
    private val workflowMethod: Method,
    // don't pass it down to other classes, it's a "cached" instance for internal usage only
    private val dataConverterWithWorkflowContext: DataConverter
  ) : KotlinWorkflowDefinition {
    private var workflowInvoker: WorkflowInboundCallsInterceptor? = null
    override suspend fun initialize() {
      val workflowContext = KotlinWorkflowInternal.rootWorkflowContext
      workflowInvoker = RootWorkflowInboundCallsInterceptor(workflowContext)
      for (workerInterceptor in workerInterceptors) {
        workflowInvoker = workerInterceptor.interceptWorkflow(workflowInvoker!!)
      }
      workflowContext.initHeadInboundCallsInterceptor(workflowInvoker!!)
      workflowInvoker!!.init(workflowContext)
    }

    @Throws(CanceledFailure::class, WorkflowExecutionException::class)
    override suspend fun execute(header: Header?, input: Payloads?): Payloads? {
      val args = dataConverterWithWorkflowContext.fromPayloads(
        Optional.ofNullable(input), workflowMethod.parameterTypes, workflowMethod.genericParameterTypes
      )
      Preconditions.checkNotNull(workflowInvoker, "initialize not called")
      val result = workflowInvoker!!.execute(WorkflowInboundCallsInterceptor.WorkflowInput(header, args))
      return if (workflowMethod.returnType == Void.TYPE) {
        null
      } else dataConverterWithWorkflowContext.toPayloads(
        result.result
      ).orElse(null)
    }

    private inner class RootWorkflowInboundCallsInterceptor(workflowContext: KotlinWorkflowContext) :
      BaseRootKotlinWorkflowInboundCallsInterceptor(workflowContext) {
      private var workflow: Any? = null

      override suspend fun init(outboundCalls: WorkflowOutboundCallsInterceptor) {
        super.init(outboundCalls)
        newInstance()
        WorkflowInternal.registerListener(workflow)
      }

      override suspend fun execute(input: WorkflowInboundCallsInterceptor.WorkflowInput): WorkflowInboundCallsInterceptor.WorkflowOutput {
        return try {
          val result = workflowMethod.invoke(workflow, *input.arguments)
          WorkflowInboundCallsInterceptor.WorkflowOutput(result)
        } catch (e: IllegalAccessException) {
          throw CheckedExceptionWrapper.wrap(e)
        } catch (e: InvocationTargetException) {
          val target = e.targetException
          throw CheckedExceptionWrapper.wrap(target)
        }
      }

      protected fun newInstance() {
        val factory = workflowInstanceFactories[workflowImplementationClass]
        workflow = if (factory != null) {
          factory.apply()
        } else {
          try {
            workflowImplementationClass.getDeclaredConstructor().newInstance()
          } catch (e: NoSuchMethodException) {
            // Error to fail workflow task as this can be fixed by a new deployment.
            throw Error(
              "Failure instantiating workflow implementation class "
                + workflowImplementationClass.name,
              e
            )
          } catch (e: InstantiationException) {
            throw Error(
              "Failure instantiating workflow implementation class "
                + workflowImplementationClass.name,
              e
            )
          } catch (e: IllegalAccessException) {
            throw Error(
              "Failure instantiating workflow implementation class "
                + workflowImplementationClass.name,
              e
            )
          } catch (e: InvocationTargetException) {
            throw Error(
              "Failure instantiating workflow implementation class "
                + workflowImplementationClass.name,
              e
            )
          }
        }
      }
    }
  }

  override fun toString(): String {
    return ("POJOWorkflowImplementationFactory{"
      + "registeredWorkflowTypes="
      + workflowDefinitions.keys
      + '}')
  }

  companion object {
    private val log = LoggerFactory.getLogger(KotlinWorkflowImplementationFactory::class.java)
    val WORKFLOW_HANDLER_STACKTRACE_CUTOFF = ImmutableSet.builder<String>() // POJO
      .add(
        ReflectionUtils.getMethodNameForStackTraceCutoff(
          KotlinWorkflowImplementation::class.java, "execute", Header::class.java, Optional::class.java
        )
      ) // Dynamic
      .add(
        ReflectionUtils.getMethodNameForStackTraceCutoff(
          DynamicKotlinWorkflowDefinition::class.java, "execute", Header::class.java, Optional::class.java
        )
      )
      .build()
  }
}