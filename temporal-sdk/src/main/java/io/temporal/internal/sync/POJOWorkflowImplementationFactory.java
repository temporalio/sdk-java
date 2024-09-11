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

package io.temporal.internal.sync;

import static io.temporal.serviceclient.CheckedExceptionWrapper.wrap;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.metadata.POJOWorkflowImplMetadata;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.common.env.ReflectionUtils;
import io.temporal.internal.replay.ReplayWorkflow;
import io.temporal.internal.replay.ReplayWorkflowFactory;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.internal.worker.WorkflowExecutorCache;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.worker.TypeAlreadyRegisteredException;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.DynamicWorkflow;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class POJOWorkflowImplementationFactory implements ReplayWorkflowFactory {
  private static final Logger log =
      LoggerFactory.getLogger(POJOWorkflowImplementationFactory.class);

  public static final ImmutableSet<String> WORKFLOW_HANDLER_STACKTRACE_CUTOFF =
      ImmutableSet.<String>builder()
          // POJO
          .add(
              ReflectionUtils.getMethodNameForStackTraceCutoff(
                  POJOWorkflowImplementation.class, "execute", Header.class, Optional.class))
          // Dynamic
          .add(
              ReflectionUtils.getMethodNameForStackTraceCutoff(
                  DynamicSyncWorkflowDefinition.class, "execute", Header.class, Optional.class))
          .build();
  private final WorkerInterceptor[] workerInterceptors;

  private final DataConverter dataConverter;
  private final List<ContextPropagator> contextPropagators;
  private final long defaultDeadlockDetectionTimeout;

  /** Key: workflow type name, Value: function that creates SyncWorkflowDefinition instance. */
  private final Map<String, Functions.Func1<WorkflowExecution, SyncWorkflowDefinition>>
      workflowDefinitions = Collections.synchronizedMap(new HashMap<>());
  /** Factories providing instances of workflow classes. */
  private final Map<Class<?>, Functions.Func<?>> workflowInstanceFactories =
      Collections.synchronizedMap(new HashMap<>());
  /** If present then it is called for any unknown workflow type. */
  private Functions.Func1<EncodedValues, ? extends DynamicWorkflow>
      dynamicWorkflowImplementationFactory;

  private final Map<String, WorkflowImplementationOptions> implementationOptions =
      Collections.synchronizedMap(new HashMap<>());

  private final WorkflowThreadExecutor workflowThreadExecutor;
  private final WorkflowExecutorCache cache;

  private final String namespace;

  public POJOWorkflowImplementationFactory(
      SingleWorkerOptions singleWorkerOptions,
      WorkflowThreadExecutor workflowThreadExecutor,
      WorkerInterceptor[] workerInterceptors,
      WorkflowExecutorCache cache,
      @Nonnull String namespace) {
    Objects.requireNonNull(singleWorkerOptions);
    this.dataConverter = singleWorkerOptions.getDataConverter();
    this.workflowThreadExecutor = Objects.requireNonNull(workflowThreadExecutor);
    this.workerInterceptors = Objects.requireNonNull(workerInterceptors);
    this.cache = cache;
    this.contextPropagators = singleWorkerOptions.getContextPropagators();
    this.defaultDeadlockDetectionTimeout = singleWorkerOptions.getDefaultDeadlockDetectionTimeout();
    this.namespace = namespace;
  }

  public void registerWorkflowImplementationTypes(
      WorkflowImplementationOptions options, Class<?>[] workflowImplementationTypes) {
    for (Class<?> type : workflowImplementationTypes) {
      registerWorkflowImplementationType(options, type);
    }
  }

  /**
   * @param clazz has to be a workflow interface class. The only exception is if it's a
   *     DynamicWorkflow class.
   */
  @SuppressWarnings("unchecked")
  public <R> void addWorkflowImplementationFactory(
      WorkflowImplementationOptions options, Class<R> clazz, Functions.Func<R> factory) {
    if (DynamicWorkflow.class.isAssignableFrom(clazz)) {
      if (dynamicWorkflowImplementationFactory != null) {
        throw new TypeAlreadyRegisteredException(
            "DynamicWorkflow",
            "An implementation of DynamicWorkflow or its factory is already registered with the worker");
      }
      dynamicWorkflowImplementationFactory =
          (unused) -> ((Func<? extends DynamicWorkflow>) factory).apply();
      return;
    }
    workflowInstanceFactories.put(clazz, factory);
    POJOWorkflowInterfaceMetadata workflowMetadata =
        POJOWorkflowInterfaceMetadata.newInstance(clazz);
    if (!workflowMetadata.getWorkflowMethod().isPresent()) {
      throw new IllegalArgumentException(
          "Workflow interface doesn't contain a method annotated with @WorkflowMethod: " + clazz);
    }
    List<POJOWorkflowMethodMetadata> methodsMetadata = workflowMetadata.getMethodsMetadata();
    for (POJOWorkflowMethodMetadata methodMetadata : methodsMetadata) {
      switch (methodMetadata.getType()) {
        case WORKFLOW:
          String typeName = methodMetadata.getName();
          if (workflowDefinitions.containsKey(typeName)) {
            throw new TypeAlreadyRegisteredException(
                typeName,
                "\"" + typeName + "\" workflow type is already registered with the worker");
          }
          workflowDefinitions.put(
              typeName,
              (execution) ->
                  new POJOWorkflowImplementation(
                      clazz,
                      null,
                      methodMetadata.getWorkflowMethod(),
                      dataConverter.withContext(
                          new WorkflowSerializationContext(namespace, execution.getWorkflowId()))));
          implementationOptions.put(typeName, options);
          break;
        case SIGNAL:
          // Signals are registered through Workflow.registerListener
          break;
      }
    }
  }

  private <T> void registerWorkflowImplementationType(
      WorkflowImplementationOptions options, Class<T> workflowImplementationClass) {
    if (DynamicWorkflow.class.isAssignableFrom(workflowImplementationClass)) {
      if (dynamicWorkflowImplementationFactory != null) {
        throw new TypeAlreadyRegisteredException(
            "DynamicWorkflow",
            "An implementation of DynamicWorkflow or its factory is already registered with the worker");
      }
      dynamicWorkflowImplementationFactory =
          (encodedValues) -> {
            try {
              try {
                return (DynamicWorkflow)
                    workflowImplementationClass.getDeclaredConstructor().newInstance();
              } catch (NoSuchMethodException e) {
                return (DynamicWorkflow)
                    workflowImplementationClass
                        .getDeclaredConstructor(EncodedValues.class)
                        .newInstance(encodedValues);
              }
            } catch (NoSuchMethodException
                | InstantiationException
                | IllegalAccessException
                | InvocationTargetException e) {
              throw wrap(e);
            }
          };
      return;
    }
    POJOWorkflowImplMetadata workflowMetadata =
        POJOWorkflowImplMetadata.newInstance(workflowImplementationClass);
    List<POJOWorkflowMethodMetadata> workflowMethods = workflowMetadata.getWorkflowMethods();
    if (workflowMethods.isEmpty()) {
      throw new IllegalArgumentException(
          "Workflow implementation doesn't implement any interface "
              + "with a workflow method annotated with @WorkflowMethod: "
              + workflowImplementationClass);
    }
    for (POJOWorkflowMethodMetadata workflowMethod : workflowMethods) {
      String workflowName = workflowMethod.getName();
      Method method = workflowMethod.getWorkflowMethod();
      Functions.Func1<WorkflowExecution, SyncWorkflowDefinition> definition =
          (execution) ->
              new POJOWorkflowImplementation(
                  workflowImplementationClass,
                  workflowMetadata.getConstructor(),
                  method,
                  dataConverter.withContext(
                      new WorkflowSerializationContext(namespace, execution.getWorkflowId())));

      if (workflowDefinitions.containsKey(workflowName)) {
        throw new IllegalStateException(
            workflowName + " workflow type is already registered with the worker");
      }
      workflowDefinitions.put(workflowName, definition);
      implementationOptions.put(workflowName, options);
    }
  }

  private SyncWorkflowDefinition getWorkflowDefinition(
      WorkflowType workflowType, WorkflowExecution workflowExecution) {
    Functions.Func1<WorkflowExecution, SyncWorkflowDefinition> factory =
        workflowDefinitions.get(workflowType.getName());
    if (factory == null) {
      if (dynamicWorkflowImplementationFactory != null) {
        return new DynamicSyncWorkflowDefinition(
            dynamicWorkflowImplementationFactory,
            workerInterceptors,
            dataConverter.withContext(
                new WorkflowSerializationContext(namespace, workflowExecution.getWorkflowId())));
      }
      // throw Error to abort the workflow task, not fail the workflow
      throw new Error(
          "Unknown workflow type \""
              + workflowType.getName()
              + "\". Known types are "
              + workflowDefinitions.keySet());
    }
    try {
      return factory.apply(workflowExecution);
    } catch (Exception e) {
      throw new Error(e);
    }
  }

  @Override
  public ReplayWorkflow getWorkflow(
      WorkflowType workflowType, WorkflowExecution workflowExecution) {
    SyncWorkflowDefinition workflow = getWorkflowDefinition(workflowType, workflowExecution);
    WorkflowImplementationOptions workflowImplementationOptions =
        implementationOptions.get(workflowType.getName());
    DataConverter dataConverterWithWorkflowContext =
        dataConverter.withContext(
            new WorkflowSerializationContext(namespace, workflowExecution.getWorkflowId()));
    return new SyncWorkflow(
        namespace,
        workflowExecution,
        workflow,
        new SignalDispatcher(dataConverterWithWorkflowContext),
        new QueryDispatcher(dataConverterWithWorkflowContext),
        new UpdateDispatcher(dataConverterWithWorkflowContext),
        workflowImplementationOptions,
        dataConverter,
        workflowThreadExecutor,
        cache,
        contextPropagators,
        defaultDeadlockDetectionTimeout);
  }

  @Override
  public boolean isAnyTypeSupported() {
    return !workflowDefinitions.isEmpty() || dynamicWorkflowImplementationFactory != null;
  }

  private class POJOWorkflowImplementation implements SyncWorkflowDefinition {
    private final Class<?> workflowImplementationClass;
    private final Method workflowMethod;
    private final Constructor<?> ctor;
    private WorkflowInboundCallsInterceptor workflowInvoker;
    // don't pass it down to other classes, it's a "cached" instance for internal usage only
    private final DataConverter dataConverterWithWorkflowContext;

    public POJOWorkflowImplementation(
        Class<?> workflowImplementationClass,
        Constructor<?> ctor,
        Method workflowMethod,
        DataConverter dataConverterWithWorkflowContext) {
      this.workflowImplementationClass = workflowImplementationClass;
      this.ctor = ctor;
      this.workflowMethod = workflowMethod;
      this.dataConverterWithWorkflowContext = dataConverterWithWorkflowContext;
    }

    @Override
    public void initialize(Optional<Payloads> input) {
      SyncWorkflowContext workflowContext = WorkflowInternal.getRootWorkflowContext();
      workflowInvoker = new RootWorkflowInboundCallsInterceptor(workflowContext, input);
      for (WorkerInterceptor workerInterceptor : workerInterceptors) {
        workflowInvoker = workerInterceptor.interceptWorkflow(workflowInvoker);
      }
      workflowContext.initHeadInboundCallsInterceptor(workflowInvoker);
      workflowInvoker.init(workflowContext);
    }

    @Override
    public Optional<Payloads> execute(Header header, Optional<Payloads> input)
        throws CanceledFailure, WorkflowExecutionException {

      Object[] args =
          dataConverterWithWorkflowContext.fromPayloads(
              input, workflowMethod.getParameterTypes(), workflowMethod.getGenericParameterTypes());
      Preconditions.checkNotNull(workflowInvoker, "initialize not called");
      WorkflowInboundCallsInterceptor.WorkflowOutput result =
          workflowInvoker.execute(new WorkflowInboundCallsInterceptor.WorkflowInput(header, args));
      if (workflowMethod.getReturnType() == Void.TYPE) {
        return Optional.empty();
      }
      return dataConverterWithWorkflowContext.toPayloads(result.getResult());
    }

    private class RootWorkflowInboundCallsInterceptor
        extends BaseRootWorkflowInboundCallsInterceptor {
      private Object workflow;
      private Optional<Payloads> input;

      public RootWorkflowInboundCallsInterceptor(
          SyncWorkflowContext workflowContext, Optional<Payloads> input) {
        super(workflowContext);
        this.input = input;
      }

      @Override
      public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
        super.init(outboundCalls);
        newInstance(input);
        WorkflowInternal.registerListener(workflow);
      }

      @Override
      public WorkflowOutput execute(WorkflowInput input) {
        try {
          Object result = workflowMethod.invoke(workflow, input.getArguments());
          return new WorkflowOutput(result);
        } catch (IllegalAccessException e) {
          throw wrap(e);
        } catch (InvocationTargetException e) {
          Throwable target = e.getTargetException();
          throw wrap(target);
        }
      }

      protected void newInstance(Optional<Payloads> input) {
        Func<?> factory = workflowInstanceFactories.get(workflowImplementationClass);
        if (factory != null) {
          workflow = factory.apply();
        } else {
          if (ctor != null) {
            try {
              workflow =
                  ctor.newInstance(
                      dataConverterWithWorkflowContext.fromPayloads(
                          input, ctor.getParameterTypes(), ctor.getGenericParameterTypes()));
            } catch (InstantiationException
                | IllegalAccessException
                | InvocationTargetException e) {
              throw wrap(e);
            }
          } else {
            legacyNewInstance();
          }
        }
      }

      private void legacyNewInstance() {
        try {
          workflow = workflowImplementationClass.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException
            | InstantiationException
            | IllegalAccessException
            | InvocationTargetException e) {
          // Error to fail workflow task as this can be fixed by a new deployment.
          throw new Error(
              "Failure instantiating workflow implementation class "
                  + workflowImplementationClass.getName(),
              e);
        }
      }
    }
  }

  @Override
  public String toString() {
    return "POJOWorkflowImplementationFactory{"
        + "registeredWorkflowTypes="
        + workflowDefinitions.keySet()
        + '}';
  }
}
