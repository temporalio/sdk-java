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

import com.google.common.base.Strings;
import com.google.common.reflect.TypeToken;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.BatchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientInterceptor;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.converter.DataConverter;
import io.temporal.internal.external.GenericWorkflowClientExternalImpl;
import io.temporal.internal.external.ManualActivityCompletionClientFactory;
import io.temporal.internal.external.ManualActivityCompletionClientFactoryImpl;
import io.temporal.internal.sync.WorkflowInvocationHandler.InvocationType;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowMethod;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorkflowClientInternal implements WorkflowClient {

  private final GenericWorkflowClientExternalImpl genericClient;
  private final WorkflowClientOptions options;
  private final ManualActivityCompletionClientFactory manualActivityCompletionClientFactory;
  private final DataConverter dataConverter;
  private final WorkflowClientInterceptor[] interceptors;
  private final WorkflowServiceStubs workflowServiceStubs;

  private AtomicBoolean shutdown = new AtomicBoolean();

  /**
   * Creates client that connects to an instance of the Temporal Service.
   *
   * @param service client to the Temporal Service endpoint.
   * @param options Options (like {@link io.temporal.converter.DataConverter} override) for
   *     configuring client.
   */
  public static WorkflowClient newInstance(
      WorkflowServiceStubs service, WorkflowClientOptions options) {
    return new WorkflowClientInternal(service, options);
  }

  private WorkflowClientInternal(
      WorkflowServiceStubs workflowServiceStubs, WorkflowClientOptions options) {
    options = WorkflowClientOptions.newBuilder(options).validateAndBuildWithDefaults();
    this.options = options;
    this.workflowServiceStubs = workflowServiceStubs;
    this.genericClient =
        new GenericWorkflowClientExternalImpl(
            workflowServiceStubs,
            options.getDomain(),
            options.getIdentity(),
            options.getMetricsScope());
    this.dataConverter = options.getDataConverter();
    this.interceptors = options.getInterceptors();
    this.manualActivityCompletionClientFactory =
        new ManualActivityCompletionClientFactoryImpl(
            workflowServiceStubs, options.getDomain(), dataConverter, options.getMetricsScope());
  }

  @Override
  public WorkflowServiceStubs getWorkflowServiceStubs() {
    return workflowServiceStubs;
  }

  @Override
  public <T> T newWorkflowStub(Class<T> workflowInterface) {
    return newWorkflowStub(workflowInterface, (WorkflowOptions) null);
  }

  @Override
  public WorkflowClientOptions getOptions() {
    return options;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T newWorkflowStub(Class<T> workflowInterface, WorkflowOptions options) {
    checkAnnotation(workflowInterface, WorkflowMethod.class);
    WorkflowInvocationHandler invocationHandler =
        new WorkflowInvocationHandler(
            workflowInterface, genericClient, options, dataConverter, interceptors);
    return (T)
        Proxy.newProxyInstance(
            WorkflowInternal.class.getClassLoader(),
            new Class<?>[] {workflowInterface, StubMarker.class},
            invocationHandler);
  }

  @SafeVarargs
  private static <T> void checkAnnotation(
      Class<T> workflowInterface, Class<? extends Annotation>... annotationClasses) {
    TypeToken<?>.TypeSet interfaces = TypeToken.of(workflowInterface).getTypes().interfaces();
    if (interfaces.isEmpty()) {
      throw new IllegalArgumentException("Workflow must implement at least one interface");
    }
    for (TypeToken<?> i : interfaces) {
      for (Method method : i.getRawType().getMethods()) {
        for (Class<? extends Annotation> annotationClass : annotationClasses) {
          Object workflowMethod = method.getAnnotation(annotationClass);
          if (workflowMethod != null) {
            return;
          }
        }
      }
    }
    throw new IllegalArgumentException(
        "Workflow interface "
            + workflowInterface.getName()
            + " doesn't have method annotated with any of "
            + Arrays.toString(annotationClasses));
  }

  @Override
  public <T> T newWorkflowStub(Class<T> workflowInterface, String workflowId) {
    return newWorkflowStub(workflowInterface, workflowId, Optional.empty());
  }

  @Override
  public <T> T newWorkflowStub(
      Class<T> workflowInterface, String workflowId, Optional<String> runId) {
    checkAnnotation(workflowInterface, WorkflowMethod.class, QueryMethod.class);
    if (Strings.isNullOrEmpty(workflowId)) {
      throw new IllegalArgumentException("workflowId is null or empty");
    }
    WorkflowExecution execution =
        WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId(runId.orElse("")).build();

    WorkflowInvocationHandler invocationHandler =
        new WorkflowInvocationHandler(
            workflowInterface, genericClient, execution, dataConverter, interceptors);
    @SuppressWarnings("unchecked")
    T result =
        (T)
            Proxy.newProxyInstance(
                WorkflowInternal.class.getClassLoader(),
                new Class<?>[] {workflowInterface, StubMarker.class},
                invocationHandler);
    return result;
  }

  @Override
  public WorkflowStub newUntypedWorkflowStub(String workflowType, WorkflowOptions options) {
    WorkflowStub result = new WorkflowStubImpl(genericClient, dataConverter, workflowType, options);
    for (WorkflowClientInterceptor i : interceptors) {
      result = i.newUntypedWorkflowStub(workflowType, options, result);
    }
    return result;
  }

  @Override
  public WorkflowStub newUntypedWorkflowStub(
      String workflowId, Optional<String> runId, Optional<String> workflowType) {
    WorkflowExecution execution =
        WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId(runId.get()).build();
    return newUntypedWorkflowStub(execution, workflowType);
  }

  @Override
  public WorkflowStub newUntypedWorkflowStub(
      WorkflowExecution execution, Optional<String> workflowType) {
    return new WorkflowStubImpl(genericClient, dataConverter, workflowType, execution);
  }

  @Override
  public ActivityCompletionClient newActivityCompletionClient() {
    ActivityCompletionClient result =
        new ActivityCompletionClientImpl(manualActivityCompletionClientFactory);
    for (WorkflowClientInterceptor i : interceptors) {
      result = i.newActivityCompletionClient(result);
    }
    return result;
  }

  @Override
  public BatchRequest newSignalWithStartRequest() {
    return new SignalWithStartBatchRequest();
  }

  @Override
  public WorkflowExecution signalWithStart(BatchRequest signalWithStartBatch) {
    return ((SignalWithStartBatchRequest) signalWithStartBatch).invoke();
  }

  public static WorkflowExecution start(Functions.Proc workflow) {
    WorkflowInvocationHandler.initAsyncInvocation(InvocationType.START);
    try {
      workflow.apply();
      return WorkflowInvocationHandler.getAsyncInvocationResult(WorkflowExecution.class);
    } finally {
      WorkflowInvocationHandler.closeAsyncInvocation();
    }
  }

  public static <A1> WorkflowExecution start(Functions.Proc1<A1> workflow, A1 arg1) {
    return start(() -> workflow.apply(arg1));
  }

  public static <A1, A2> WorkflowExecution start(
      Functions.Proc2<A1, A2> workflow, A1 arg1, A2 arg2) {
    return start(() -> workflow.apply(arg1, arg2));
  }

  public static <A1, A2, A3> WorkflowExecution start(
      Functions.Proc3<A1, A2, A3> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return start(() -> workflow.apply(arg1, arg2, arg3));
  }

  public static <A1, A2, A3, A4> WorkflowExecution start(
      Functions.Proc4<A1, A2, A3, A4> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4));
  }

  public static <A1, A2, A3, A4, A5> WorkflowExecution start(
      Functions.Proc5<A1, A2, A3, A4, A5> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
  }

  public static <A1, A2, A3, A4, A5, A6> WorkflowExecution start(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  public static <R> WorkflowExecution start(Functions.Func<R> workflow) {
    return start(
        () -> { // Need {} to call start(Proc...)
          workflow.apply();
        });
  }

  public static <A1, R> WorkflowExecution start(Functions.Func1<A1, R> workflow, A1 arg1) {
    return start(() -> workflow.apply(arg1));
  }

  public static <A1, A2, R> WorkflowExecution start(
      Functions.Func2<A1, A2, R> workflow, A1 arg1, A2 arg2) {
    return start(() -> workflow.apply(arg1, arg2));
  }

  public static <A1, A2, A3, R> WorkflowExecution start(
      Functions.Func3<A1, A2, A3, R> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return start(() -> workflow.apply(arg1, arg2, arg3));
  }

  public static <A1, A2, A3, A4, R> WorkflowExecution start(
      Functions.Func4<A1, A2, A3, A4, R> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4));
  }

  public static <A1, A2, A3, A4, A5, R> WorkflowExecution start(
      Functions.Func5<A1, A2, A3, A4, A5, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
  }

  public static <A1, A2, A3, A4, A5, A6, R> WorkflowExecution start(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return start(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  @SuppressWarnings("unchecked")
  public static CompletableFuture<Void> execute(Functions.Proc workflow) {
    WorkflowInvocationHandler.initAsyncInvocation(InvocationType.EXECUTE);
    try {
      workflow.apply();
      return WorkflowInvocationHandler.getAsyncInvocationResult(CompletableFuture.class);
    } finally {
      WorkflowInvocationHandler.closeAsyncInvocation();
    }
  }

  public static <A1> CompletableFuture<Void> execute(Functions.Proc1<A1> workflow, A1 arg1) {
    return execute(() -> workflow.apply(arg1));
  }

  public static <A1, A2> CompletableFuture<Void> execute(
      Functions.Proc2<A1, A2> workflow, A1 arg1, A2 arg2) {
    return execute(() -> workflow.apply(arg1, arg2));
  }

  public static <A1, A2, A3> CompletableFuture<Void> execute(
      Functions.Proc3<A1, A2, A3> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return execute(() -> workflow.apply(arg1, arg2, arg3));
  }

  public static <A1, A2, A3, A4> CompletableFuture<Void> execute(
      Functions.Proc4<A1, A2, A3, A4> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4));
  }

  public static <A1, A2, A3, A4, A5> CompletableFuture<Void> execute(
      Functions.Proc5<A1, A2, A3, A4, A5> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
  }

  public static <A1, A2, A3, A4, A5, A6> CompletableFuture<Void> execute(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }

  @SuppressWarnings("unchecked")
  public static <R> CompletableFuture<R> execute(Functions.Func<R> workflow) {
    return (CompletableFuture<R>)
        execute(
            () -> {
              // Need {} to call execute(Proc...)
              workflow.apply();
            });
  }

  public static <A1, R> CompletableFuture<R> execute(Functions.Func1<A1, R> workflow, A1 arg1) {
    return execute(() -> workflow.apply(arg1));
  }

  public static <A1, A2, R> CompletableFuture<R> execute(
      Functions.Func2<A1, A2, R> workflow, A1 arg1, A2 arg2) {
    return execute(() -> workflow.apply(arg1, arg2));
  }

  public static <A1, A2, A3, R> CompletableFuture<R> execute(
      Functions.Func3<A1, A2, A3, R> workflow, A1 arg1, A2 arg2, A3 arg3) {
    return execute(() -> workflow.apply(arg1, arg2, arg3));
  }

  public static <A1, A2, A3, A4, R> CompletableFuture<R> execute(
      Functions.Func4<A1, A2, A3, A4, R> workflow, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4));
  }

  public static <A1, A2, A3, A4, A5, R> CompletableFuture<R> execute(
      Functions.Func5<A1, A2, A3, A4, A5, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5));
  }

  public static <A1, A2, A3, A4, A5, A6, R> CompletableFuture<R> execute(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> workflow,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    return execute(() -> workflow.apply(arg1, arg2, arg3, arg4, arg5, arg6));
  }
}
