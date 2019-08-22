/*
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

package com.uber.cadence.internal.sync;

import com.google.common.base.Strings;
import com.google.common.reflect.TypeToken;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.client.ActivityCompletionClient;
import com.uber.cadence.client.BatchRequest;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientInterceptor;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.client.WorkflowStub;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.external.GenericWorkflowClientExternalImpl;
import com.uber.cadence.internal.external.ManualActivityCompletionClientFactory;
import com.uber.cadence.internal.external.ManualActivityCompletionClientFactoryImpl;
import com.uber.cadence.internal.sync.WorkflowInvocationHandler.InvocationType;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.serviceclient.WorkflowServiceTChannel;
import com.uber.cadence.workflow.Functions;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.WorkflowMethod;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class WorkflowClientInternal implements WorkflowClient {

  private final GenericWorkflowClientExternalImpl genericClient;
  private final ManualActivityCompletionClientFactory manualActivityCompletionClientFactory;
  private final DataConverter dataConverter;
  private final WorkflowClientInterceptor[] interceptors;
  private final IWorkflowService workflowService;

  /**
   * Creates worker that connects to the local instance of the Cadence Service that listens on a
   * default port (7933).
   *
   * @param domain domain that worker uses to poll.
   */
  public static WorkflowClient newInstance(String domain) {
    return new WorkflowClientInternal(
        new WorkflowServiceTChannel(), domain, new WorkflowClientOptions.Builder().build());
  }

  /**
   * Creates worker that connects to the local instance of the Cadence Service that listens on a
   * default port (7933).
   *
   * @param domain domain that worker uses to poll.
   * @param options Options (like {@link com.uber.cadence.converter.DataConverter} override) for
   *     configuring client.
   */
  public static WorkflowClient newInstance(String domain, WorkflowClientOptions options) {
    return new WorkflowClientInternal(new WorkflowServiceTChannel(), domain, options);
  }

  /**
   * Creates client that connects to an instance of the Cadence Service.
   *
   * @param host of the Cadence Service endpoint
   * @param port of the Cadence Service endpoint
   * @param domain domain that worker uses to poll.
   */
  public static WorkflowClient newInstance(String host, int port, String domain) {
    return new WorkflowClientInternal(
        new WorkflowServiceTChannel(host, port),
        domain,
        new WorkflowClientOptions.Builder().build());
  }

  /**
   * Creates client that connects to an instance of the Cadence Service.
   *
   * @param host of the Cadence Service endpoint
   * @param port of the Cadence Service endpoint
   * @param domain domain that worker uses to poll.
   * @param options Options (like {@link com.uber.cadence.converter.DataConverter} override) for
   *     configuring client.
   */
  public static WorkflowClient newInstance(
      String host, int port, String domain, WorkflowClientOptions options) {
    return new WorkflowClientInternal(new WorkflowServiceTChannel(host, port), domain, options);
  }

  /**
   * Creates client that connects to an instance of the Cadence Service.
   *
   * @param service client to the Cadence Service endpoint.
   * @param domain domain that worker uses to poll.
   */
  public static WorkflowClient newInstance(IWorkflowService service, String domain) {
    return new WorkflowClientInternal(service, domain, null);
  }

  /**
   * Creates client that connects to an instance of the Cadence Service.
   *
   * @param service client to the Cadence Service endpoint.
   * @param domain domain that worker uses to poll.
   * @param options Options (like {@link com.uber.cadence.converter.DataConverter} override) for
   *     configuring client.
   */
  public static WorkflowClient newInstance(
      IWorkflowService service, String domain, WorkflowClientOptions options) {
    return new WorkflowClientInternal(service, domain, options);
  }

  private WorkflowClientInternal(
      IWorkflowService service, String domain, WorkflowClientOptions options) {
    if (options == null) {
      options = new WorkflowClientOptions.Builder().build();
    }
    this.workflowService = service;
    this.genericClient =
        new GenericWorkflowClientExternalImpl(service, domain, options.getMetricsScope());
    this.dataConverter = options.getDataConverter();
    this.interceptors = options.getInterceptors();
    this.manualActivityCompletionClientFactory =
        new ManualActivityCompletionClientFactoryImpl(
            service, domain, dataConverter, options.getMetricsScope());
  }

  @Override
  public <T> T newWorkflowStub(Class<T> workflowInterface) {
    return newWorkflowStub(workflowInterface, (WorkflowOptions) null);
  }

  @Override
  public String getDomain() {
    return genericClient.getDomain();
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
            new Class<?>[] {workflowInterface},
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
    WorkflowExecution execution = new WorkflowExecution();
    execution.setWorkflowId(workflowId);
    if (runId.isPresent()) {
      execution.setRunId(runId.get());
    }
    WorkflowInvocationHandler invocationHandler =
        new WorkflowInvocationHandler(
            workflowInterface, genericClient, execution, dataConverter, interceptors);
    @SuppressWarnings("unchecked")
    T result =
        (T)
            Proxy.newProxyInstance(
                WorkflowInternal.class.getClassLoader(),
                new Class<?>[] {workflowInterface},
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
    WorkflowExecution execution = new WorkflowExecution().setWorkflowId(workflowId);
    if (runId.isPresent()) {
      execution.setRunId(runId.get());
    }
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

  @Override
  public void close() {
    this.workflowService.close();
  }
}
