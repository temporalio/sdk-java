/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.sync;

import static io.temporal.worker.WorkflowErrorPolicy.FailWorkflow;

import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.FailureConverter;
import io.temporal.failure.TemporalFailure;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.ReplayWorkflow;
import io.temporal.internal.replay.ReplayWorkflowFactory;
import io.temporal.internal.replay.WorkflowExecutorCache;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class POJOWorkflowImplementationFactory implements ReplayWorkflowFactory {

  private static final Logger log =
      LoggerFactory.getLogger(POJOWorkflowImplementationFactory.class);
  private static final byte[] EMPTY_BLOB = {};
  private final WorkflowInterceptor[] workflowInterceptors;

  private DataConverter dataConverter;
  private final List<ContextPropagator> contextPropagators;

  /** Key: workflow type name, Value: function that creates SyncWorkflowDefinition instance. */
  private final Map<String, Functions.Func<SyncWorkflowDefinition>> workflowDefinitions =
      Collections.synchronizedMap(new HashMap<>());

  private final Map<String, WorkflowImplementationOptions> implementationOptions =
      Collections.synchronizedMap(new HashMap<>());

  private final Map<Class<?>, Functions.Func<?>> workflowImplementationFactories =
      Collections.synchronizedMap(new HashMap<>());

  private final ExecutorService threadPool;
  private final WorkflowExecutorCache cache;

  POJOWorkflowImplementationFactory(
      DataConverter dataConverter,
      ExecutorService threadPool,
      WorkflowInterceptor[] workflowInterceptors,
      WorkflowExecutorCache cache,
      List<ContextPropagator> contextPropagators) {
    this.dataConverter = Objects.requireNonNull(dataConverter);
    this.threadPool = Objects.requireNonNull(threadPool);
    this.workflowInterceptors = Objects.requireNonNull(workflowInterceptors);
    this.cache = cache;
    this.contextPropagators = contextPropagators;
  }

  void addWorkflowImplementationTypes(
      WorkflowImplementationOptions options, Class<?>[] workflowImplementationTypes) {
    for (Class<?> type : workflowImplementationTypes) {
      addWorkflowImplementationType(options, type);
    }
  }

  <R> void addWorkflowImplementationFactory(Class<R> clazz, Functions.Func<R> factory) {
    WorkflowImplementationOptions unitTestingOptions =
        WorkflowImplementationOptions.newBuilder().setWorkflowErrorPolicy(FailWorkflow).build();
    addWorkflowImplementationFactory(unitTestingOptions, clazz, factory);
  }

  <R> void addWorkflowImplementationFactory(
      WorkflowImplementationOptions options, Class<R> clazz, Functions.Func<R> factory) {
    workflowImplementationFactories.put(clazz, factory);
    POJOWorkflowInterfaceMetadata workflowMetadata =
        POJOWorkflowInterfaceMetadata.newInstance(clazz);
    if (!workflowMetadata.getWorkflowMethod().isPresent()) {
      throw new IllegalArgumentException(
          "Workflow interface doesn't contain a method annotated with @WorkflowMethod: " + clazz);
    }
    List<POJOWorkflowMethodMetadata> methodsMetadata = workflowMetadata.getMethodsMetadata();
    Map<String, Method> signalHandlers = new HashMap<>();
    for (POJOWorkflowMethodMetadata methodMetadata : methodsMetadata) {
      switch (methodMetadata.getType()) {
        case WORKFLOW:
          String workflowName = methodMetadata.getName();
          if (workflowDefinitions.containsKey(workflowName)) {
            throw new IllegalStateException(
                workflowName + " workflow type is already registered with the worker");
          }
          workflowDefinitions.put(
              workflowName,
              () ->
                  new POJOWorkflowImplementation(
                      clazz, methodMetadata.getWorkflowMethod(), signalHandlers));
          implementationOptions.put(workflowName, options);
          break;
        case SIGNAL:
          signalHandlers.put(methodMetadata.getName(), methodMetadata.getWorkflowMethod());
          break;
      }
    }
  }

  private void addWorkflowImplementationType(
      WorkflowImplementationOptions options, Class<?> workflowImplementationClass) {
    POJOWorkflowImplMetadata workflowMetadata =
        POJOWorkflowImplMetadata.newInstance(workflowImplementationClass);
    Set<String> workflowMethodTypes = workflowMetadata.getWorkflowTypes();
    Set<String> signalTypes = workflowMetadata.getSignalTypes();
    Map<String, Method> signalHandlers = new HashMap<>();
    boolean hasWorkflowMethod = false;
    for (String workflowType : workflowMethodTypes) {
      POJOWorkflowMethodMetadata methodMetadata =
          workflowMetadata.getWorkflowMethodMetadata(workflowType);
      Method method = methodMetadata.getWorkflowMethod();
      Functions.Func<SyncWorkflowDefinition> factory =
          () -> new POJOWorkflowImplementation(workflowImplementationClass, method, signalHandlers);

      String workflowName = methodMetadata.getName();
      if (workflowDefinitions.containsKey(workflowName)) {
        throw new IllegalStateException(
            workflowName + " workflow type is already registered with the worker");
      }
      workflowDefinitions.put(workflowName, factory);
      implementationOptions.put(workflowName, options);
      hasWorkflowMethod = true;
    }
    for (String signalType : signalTypes) {
      POJOWorkflowMethodMetadata methodMetadata =
          workflowMetadata.getSignalMethodMetadata(signalType);
      signalHandlers.put(methodMetadata.getName(), methodMetadata.getWorkflowMethod());
    }
    if (!hasWorkflowMethod) {
      throw new IllegalArgumentException(
          "Workflow implementation doesn't implement any interface "
              + "with a workflow method annotated with @WorkflowMethod: "
              + workflowImplementationClass);
    }
  }

  private SyncWorkflowDefinition getWorkflowDefinition(WorkflowType workflowType) {
    Functions.Func<SyncWorkflowDefinition> factory =
        workflowDefinitions.get(workflowType.getName());
    if (factory == null) {
      // throw Error to abort the workflow task task, not fail the workflow
      throw new Error(
          "Unknown workflow type \""
              + workflowType.getName()
              + "\". Known types are "
              + workflowDefinitions.keySet());
    }
    try {
      return factory.apply();
    } catch (Exception e) {
      throw new Error(e);
    }
  }

  public void setDataConverter(DataConverter dataConverter) {
    this.dataConverter = dataConverter;
  }

  @Override
  public ReplayWorkflow getWorkflow(WorkflowType workflowType) {
    SyncWorkflowDefinition workflow = getWorkflowDefinition(workflowType);
    WorkflowImplementationOptions options = implementationOptions.get(workflowType.getName());
    return new SyncWorkflow(
        workflow, options, dataConverter, threadPool, cache, contextPropagators);
  }

  @Override
  public boolean isAnyTypeSupported() {
    return !workflowDefinitions.isEmpty();
  }

  private class POJOWorkflowImplementation implements SyncWorkflowDefinition {

    private final Method workflowMethod;
    private final Class<?> workflowImplementationClass;
    private final Map<String, Method> signalHandlers;
    private Object workflow;
    private WorkflowInboundCallsInterceptor workflowInvoker;

    public POJOWorkflowImplementation(
        Class<?> workflowImplementationClass,
        Method workflowMethod,
        Map<String, Method> signalHandlers) {
      this.workflowMethod = workflowMethod;
      this.workflowImplementationClass = workflowImplementationClass;
      this.signalHandlers = signalHandlers;
    }

    @Override
    public void initialize() {
      workflowInvoker = new RootWorkflowInboundCallsInterceptor();
      for (WorkflowInterceptor workflowInterceptor : workflowInterceptors) {
        workflowInvoker = workflowInterceptor.interceptWorkflow(workflowInvoker);
      }
      workflowInvoker.init(WorkflowInternal.getRootWorkflowContext());
    }

    @Override
    public Optional<Payloads> execute(Optional<Payloads> input)
        throws CanceledFailure, WorkflowExecutionException {
      Object[] args =
          DataConverter.arrayFromPayloads(
              dataConverter,
              input,
              workflowMethod.getParameterTypes(),
              workflowMethod.getGenericParameterTypes());
      Preconditions.checkNotNull(workflowInvoker, "initialize not called");
      Object result = workflowInvoker.execute(args);
      if (workflowMethod.getReturnType() == Void.TYPE) {
        return Optional.empty();
      }
      return dataConverter.toPayloads(result);
    }

    private void newInstance() {
      if (workflow != null) {
        throw new IllegalStateException("Already called");
      }
      Func<?> factory = workflowImplementationFactories.get(workflowImplementationClass);
      if (factory != null) {
        workflow = factory.apply();
      } else {
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

    private class RootWorkflowInboundCallsInterceptor implements WorkflowInboundCallsInterceptor {

      @Override
      public Object execute(Object[] arguments) {
        WorkflowInfo info = Workflow.getInfo();
        try {
          return workflowMethod.invoke(workflow, arguments);
        } catch (IllegalAccessException e) {
          throw new Error(mapToWorkflowExecutionException(e, dataConverter));
        } catch (InvocationTargetException e) {
          Throwable targetException = e.getTargetException();
          if (targetException instanceof Error) {
            throw (Error) targetException;
          }
          // Cancellation should be delivered as it impacts which command closes a
          // workflow.
          if (targetException instanceof CanceledFailure) {
            throw (CanceledFailure) targetException;
          }
          if (log.isErrorEnabled()) {
            log.error(
                "Workflow execution failure "
                    + "WorkflowId="
                    + info.getWorkflowId()
                    + ", RunId="
                    + info.getRunId()
                    + ", WorkflowType="
                    + info.getWorkflowType(),
                targetException);
          }
          throw mapToWorkflowExecutionException(targetException, dataConverter);
        }
      }

      @Override
      public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
        WorkflowInternal.getRootWorkflowContext().setHeadInterceptor(outboundCalls);
        newInstance();
        WorkflowInternal.registerListener(workflow);
      }

      @Override
      public void processSignal(String signalName, Object[] arguments, long eventId) {
        Method signalMethod = signalHandlers.get(signalName);
        try {
          signalMethod.invoke(workflow, arguments);
        } catch (IllegalAccessException e) {
          throw new Error("Failure processing \"" + signalName + "\" at eventId " + eventId, e);
        } catch (InvocationTargetException e) {
          Throwable targetException = e.getTargetException();
          if (targetException instanceof DataConverterException) {
            logSerializationException(
                signalName, eventId, (DataConverterException) targetException);
          } else if (targetException instanceof Error) {
            throw (Error) targetException;
          } else {
            throw new Error(
                "Failure processing \"" + signalName + "\" at eventId " + eventId, targetException);
          }
        }
      }
    }
  }

  void logSerializationException(
      String signalName, Long eventId, DataConverterException exception) {
    log.error(
        "Failure deserializing signal input for \""
            + signalName
            + "\" at eventId "
            + eventId
            + ". Dropping it.",
        exception);
    Workflow.getMetricsScope().counter(MetricsType.CORRUPTED_SIGNALS_COUNTER).inc(1);
  }

  static WorkflowExecutionException mapToWorkflowExecutionException(
      Throwable exception, DataConverter dataConverter) {
    if (exception instanceof TemporalFailure) {
      ((TemporalFailure) exception).setDataConverter(dataConverter);
    }
    Failure failure = FailureConverter.exceptionToFailure(exception);
    return new WorkflowExecutionException(failure);
  }

  static WorkflowExecutionException mapError(Throwable error) {
    Failure failure = FailureConverter.exceptionToFailureNoUnwrapping(error);
    return new WorkflowExecutionException(failure);
  }

  @Override
  public String toString() {
    return "POJOWorkflowImplementationFactory{"
        + "registeredWorkflowTypes="
        + workflowDefinitions.keySet()
        + '}';
  }
}
