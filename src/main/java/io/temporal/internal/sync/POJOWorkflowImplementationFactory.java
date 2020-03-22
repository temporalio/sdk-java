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

import static io.temporal.worker.NonDeterministicWorkflowPolicy.FailWorkflow;

import com.google.common.base.Preconditions;
import com.google.common.reflect.TypeToken;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.interceptors.WorkflowCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInterceptor;
import io.temporal.common.interceptors.WorkflowInvocationInterceptor;
import io.temporal.common.interceptors.WorkflowInvoker;
import io.temporal.internal.common.CheckedExceptionWrapper;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.metrics.MetricsType;
import io.temporal.internal.replay.DeciderCache;
import io.temporal.internal.replay.ReplayWorkflow;
import io.temporal.internal.replay.ReplayWorkflowFactory;
import io.temporal.internal.worker.WorkflowExecutionException;
import io.temporal.proto.common.WorkflowType;
import io.temporal.testing.SimulatedTimeoutException;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class POJOWorkflowImplementationFactory implements ReplayWorkflowFactory {

  private static final Logger log =
      LoggerFactory.getLogger(POJOWorkflowImplementationFactory.class);
  private static final byte[] EMPTY_BLOB = {};
  private final WorkflowInterceptor workflowInterceptor;

  private DataConverter dataConverter;
  private List<ContextPropagator> contextPropagators;

  /** Key: workflow type name, Value: function that creates SyncWorkflowDefinition instance. */
  private final Map<String, Functions.Func<SyncWorkflowDefinition>> workflowDefinitions =
      Collections.synchronizedMap(new HashMap<>());

  private Map<String, WorkflowImplementationOptions> implementationOptions =
      Collections.synchronizedMap(new HashMap<>());

  private final Map<Class<?>, Functions.Func<?>> workflowImplementationFactories =
      Collections.synchronizedMap(new HashMap<>());

  private final ExecutorService threadPool;
  private DeciderCache cache;

  POJOWorkflowImplementationFactory(
      DataConverter dataConverter,
      ExecutorService threadPool,
      WorkflowInterceptor workflowInterceptor,
      DeciderCache cache,
      List<ContextPropagator> contextPropagators) {
    this.dataConverter = Objects.requireNonNull(dataConverter);
    this.threadPool = Objects.requireNonNull(threadPool);
    this.workflowInterceptor = Objects.requireNonNull(workflowInterceptor);
    this.cache = cache;
    this.contextPropagators = contextPropagators;
  }

  void setWorkflowImplementationTypes(
      WorkflowImplementationOptions options, Class<?>[] workflowImplementationTypes) {
    workflowDefinitions.clear();
    for (Class<?> type : workflowImplementationTypes) {
      addWorkflowImplementationType(options, type);
    }
  }

  <R> void addWorkflowImplementationFactory(Class<R> clazz, Functions.Func<R> factory) {
    WorkflowImplementationOptions unitTestingOptions =
        new WorkflowImplementationOptions.Builder()
            .setNonDeterministicWorkflowPolicy(FailWorkflow)
            .build();
    addWorkflowImplementationFactory(unitTestingOptions, clazz, factory);
  }

  <R> void addWorkflowImplementationFactory(
      WorkflowImplementationOptions options, Class<R> clazz, Functions.Func<R> factory) {
    workflowImplementationFactories.put(clazz, factory);
    addWorkflowImplementationType(options, clazz);
  }

  private void addWorkflowImplementationType(
      WorkflowImplementationOptions options, Class<?> workflowImplementationClass) {
    TypeToken<?>.TypeSet interfaces =
        TypeToken.of(workflowImplementationClass).getTypes().interfaces();
    if (interfaces.isEmpty()) {
      throw new IllegalArgumentException("Workflow must implement at least one interface");
    }
    boolean hasWorkflowMethod = false;
    for (TypeToken<?> i : interfaces) {
      Map<String, Method> signalHandlers = new HashMap<>();
      for (Method method : i.getRawType().getMethods()) {
        WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
        QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
        SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
        int count =
            (workflowMethod == null ? 0 : 1)
                + (queryMethod == null ? 0 : 1)
                + (signalMethod == null ? 0 : 1);
        if (count > 1) {
          throw new IllegalArgumentException(
              method
                  + " must contain at most one annotation "
                  + "from @WorkflowMethod, @QueryMethod or @SignalMethod");
        }
        if (workflowMethod != null) {
          Functions.Func<SyncWorkflowDefinition> factory =
              () ->
                  new POJOWorkflowImplementation(
                      method, workflowImplementationClass, signalHandlers);

          String workflowName = workflowMethod.name();
          if (workflowName.isEmpty()) {
            workflowName = InternalUtils.getSimpleName(method);
          }
          if (workflowDefinitions.containsKey(workflowName)) {
            throw new IllegalStateException(
                workflowName + " workflow type is already registered with the worker");
          }
          workflowDefinitions.put(workflowName, factory);
          implementationOptions.put(workflowName, options);
          hasWorkflowMethod = true;
        }
        if (signalMethod != null) {
          if (method.getReturnType() != Void.TYPE) {
            throw new IllegalArgumentException(
                "Method annotated with @SignalMethod " + "must have void return type: " + method);
          }
          String signalName = signalMethod.name();
          if (signalName.isEmpty()) {
            signalName = InternalUtils.getSimpleName(method);
          }
          signalHandlers.put(signalName, method);
        }
        if (queryMethod != null) {
          if (method.getReturnType() == Void.TYPE) {
            throw new IllegalArgumentException(
                "Method annotated with @QueryMethod " + "cannot have void return type: " + method);
          }
        }
      }
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
      // throw Error to abort decision, not fail the workflow
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
    private WorkflowInvoker workflowInvoker;

    POJOWorkflowImplementation(
        Method method, Class<?> workflowImplementationClass, Map<String, Method> signalHandlers) {
      this.workflowMethod = method;
      this.workflowImplementationClass = workflowImplementationClass;
      this.signalHandlers = signalHandlers;
    }

    @Override
    public void initialize() {
      workflowInvoker =
          workflowInterceptor.interceptExecuteWorkflow(
              WorkflowInternal.getRootDecisionContext(), new RootWorkflowInvocationInterceptor());
      workflowInvoker.init();
    }

    @Override
    public byte[] execute(byte[] input) throws CancellationException, WorkflowExecutionException {
      Object[] args = dataConverter.fromDataArray(input, workflowMethod.getGenericParameterTypes());
      Preconditions.checkNotNull(workflowInvoker, "initialize not called");
      Object result = workflowInvoker.execute(args);
      if (workflowMethod.getReturnType() == Void.TYPE) {
        return EMPTY_BLOB;
      }
      return dataConverter.toData(result);
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
          // Error to fail decision as this can be fixed by a new deployment.
          throw new Error(
              "Failure instantiating workflow implementation class "
                  + workflowImplementationClass.getName(),
              e);
        }
      }
    }

    /**
     * Signals that failed to deserialize are logged, but do not lead to workflow or decision
     * failure. Otherwise a single bad signal from CLI would kill any workflow. Not that throwing
     * Error leads to decision being aborted. Throwing any other exception leads to workflow
     * failure. TODO: Unknown and corrupted signals handler in application code or server side DLQ.
     */
    @Override
    public void processSignal(String signalName, byte[] input, long eventId) {
      Method signalMethod = signalHandlers.get(signalName);
      if (signalMethod == null) {
        log.error(
            "Unknown signal: "
                + signalName
                + " at eventID "
                + eventId
                + ", knownSignals="
                + signalHandlers.keySet());
        return;
      }
      try {
        Object[] args = dataConverter.fromDataArray(input, signalMethod.getGenericParameterTypes());
        Preconditions.checkNotNull(workflowInvoker, "initialize not called");
        workflowInvoker.processSignal(signalName, args, eventId);
      } catch (DataConverterException e) {
        logSerializationException(signalName, eventId, e);
      }
    }

    private class RootWorkflowInvocationInterceptor implements WorkflowInvocationInterceptor {

      @Override
      public Object execute(Object[] arguments) {
        WorkflowInfo context = Workflow.getWorkflowInfo();
        WorkflowInternal.registerQuery(workflow);
        try {
          return workflowMethod.invoke(workflow, arguments);
        } catch (IllegalAccessException e) {
          throw new Error(mapToWorkflowExecutionException(e, dataConverter));
        } catch (InvocationTargetException e) {
          Throwable targetException = e.getTargetException();
          if (targetException instanceof Error) {
            throw (Error) targetException;
          }
          // Cancellation should be delivered as it impacts which decision closes a
          // workflow.
          if (targetException instanceof CancellationException) {
            throw (CancellationException) targetException;
          }
          if (log.isErrorEnabled()) {
            log.error(
                "Workflow execution failure "
                    + "WorkflowID="
                    + context.getWorkflowId()
                    + ", RunID="
                    + context.getRunId()
                    + ", WorkflowType="
                    + context.getWorkflowType(),
                targetException);
          }
          // Cast to Exception is safe as Error is handled above.
          throw mapToWorkflowExecutionException((Exception) targetException, dataConverter);
        }
      }

      @Override
      public void init(WorkflowCallsInterceptor interceptor) {
        WorkflowInternal.getRootDecisionContext().setHeadInterceptor(interceptor);
        newInstance();
      }

      @Override
      public void processSignal(String signalName, Object[] arguments, long eventId) {
        Method signalMethod = signalHandlers.get(signalName);
        try {
          signalMethod.invoke(workflow, arguments);
        } catch (IllegalAccessException e) {
          throw new Error("Failure processing \"" + signalName + "\" at eventID " + eventId, e);
        } catch (InvocationTargetException e) {
          Throwable targetException = e.getTargetException();
          if (targetException instanceof DataConverterException) {
            logSerializationException(
                signalName, eventId, (DataConverterException) targetException);
          } else if (targetException instanceof Error) {
            throw (Error) targetException;
          } else {
            throw new Error(
                "Failure processing \"" + signalName + "\" at eventID " + eventId, targetException);
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
            + "\" at eventID "
            + eventId
            + ". Dropping it.",
        exception);
    Workflow.getMetricsScope().counter(MetricsType.CORRUPTED_SIGNALS_COUNTER).inc(1);
  }

  static WorkflowExecutionException mapToWorkflowExecutionException(
      Exception failure, DataConverter dataConverter) {
    failure = CheckedExceptionWrapper.unwrap(failure);
    // Only expected during unit tests.
    if (failure instanceof SimulatedTimeoutException) {
      SimulatedTimeoutException timeoutException = (SimulatedTimeoutException) failure;
      failure =
          new SimulatedTimeoutExceptionInternal(
              timeoutException.getTimeoutType(),
              dataConverter.toData(timeoutException.getDetails()));
    }

    return new WorkflowExecutionException(
        failure.getClass().getName(), dataConverter.toData(failure));
  }

  static WorkflowExecutionException mapError(Error failure, DataConverter dataConverter) {
    return new WorkflowExecutionException(
        failure.getClass().getName(), dataConverter.toData(failure));
  }

  @Override
  public String toString() {
    return "POJOWorkflowImplementationFactory{"
        + "registeredWorkflowTypes="
        + workflowDefinitions.keySet()
        + '}';
  }
}
