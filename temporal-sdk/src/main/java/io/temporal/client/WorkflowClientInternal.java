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

package io.temporal.client;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import com.google.common.base.Strings;
import com.google.common.collect.Iterators;
import com.google.common.reflect.TypeToken;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowInvocationHandler.InvocationType;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.client.RootWorkflowClientInvoker;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.client.external.GenericWorkflowClientImpl;
import io.temporal.internal.client.external.ManualActivityCompletionClientFactory;
import io.temporal.internal.sync.StubMarker;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowMethod;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

final class WorkflowClientInternal implements WorkflowClient {

  private final GenericWorkflowClient genericClient;
  private final WorkflowClientOptions options;
  private final ManualActivityCompletionClientFactory manualActivityCompletionClientFactory;
  private final WorkflowClientInterceptor[] interceptors;
  private final WorkflowClientCallsInterceptor workflowClientCallsInvoker;
  private final WorkflowServiceStubs workflowServiceStubs;
  private final Scope metricsScope;

  /**
   * Creates client that connects to an instance of the Temporal Service. Cannot be used from within
   * workflow code.
   *
   * @param service client to the Temporal Service endpoint.
   * @param options Options (like {@link io.temporal.common.converter.DataConverter} override) for
   *     configuring client.
   */
  public static WorkflowClient newInstance(
      WorkflowServiceStubs service, WorkflowClientOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new WorkflowClientInternal(service, options), WorkflowClient.class);
  }

  private WorkflowClientInternal(
      WorkflowServiceStubs workflowServiceStubs, WorkflowClientOptions options) {
    options = WorkflowClientOptions.newBuilder(options).validateAndBuildWithDefaults();
    this.options = options;
    this.workflowServiceStubs = workflowServiceStubs;
    this.metricsScope =
        workflowServiceStubs
            .getOptions()
            .getMetricsScope()
            .tagged(MetricsTag.defaultTags(options.getNamespace()));
    this.genericClient = new GenericWorkflowClientImpl(workflowServiceStubs, metricsScope);
    this.interceptors = options.getInterceptors();
    this.workflowClientCallsInvoker = initializeClientInvoker();
    this.manualActivityCompletionClientFactory =
        ManualActivityCompletionClientFactory.newFactory(
            workflowServiceStubs,
            options.getNamespace(),
            options.getIdentity(),
            options.getDataConverter());
  }

  private WorkflowClientCallsInterceptor initializeClientInvoker() {
    WorkflowClientCallsInterceptor workflowClientInvoker =
        new RootWorkflowClientInvoker(genericClient, options);
    for (WorkflowClientInterceptor clientInterceptor : interceptors) {
      workflowClientInvoker =
          clientInterceptor.workflowClientCallsInterceptor(workflowClientInvoker);
    }
    return workflowClientInvoker;
  }

  @Override
  public WorkflowServiceStubs getWorkflowServiceStubs() {
    return workflowServiceStubs;
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
            workflowInterface, this.getOptions(), workflowClientCallsInvoker, options);
    return (T)
        Proxy.newProxyInstance(
            workflowInterface.getClassLoader(),
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
    checkAnnotation(workflowInterface, WorkflowMethod.class, QueryMethod.class, SignalMethod.class);
    if (Strings.isNullOrEmpty(workflowId)) {
      throw new IllegalArgumentException("workflowId is null or empty");
    }
    WorkflowExecution execution =
        WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId(runId.orElse("")).build();

    WorkflowInvocationHandler invocationHandler =
        new WorkflowInvocationHandler(
            workflowInterface, this.getOptions(), workflowClientCallsInvoker, execution);
    @SuppressWarnings("unchecked")
    T result =
        (T)
            Proxy.newProxyInstance(
                workflowInterface.getClassLoader(),
                new Class<?>[] {workflowInterface, StubMarker.class},
                invocationHandler);
    return result;
  }

  @Override
  public WorkflowStub newUntypedWorkflowStub(String workflowId) {
    return newUntypedWorkflowStub(workflowId, Optional.empty(), Optional.empty());
  }

  @Override
  @SuppressWarnings("deprecation")
  public WorkflowStub newUntypedWorkflowStub(String workflowType, WorkflowOptions workflowOptions) {
    WorkflowStub result =
        new WorkflowStubImpl(options, workflowClientCallsInvoker, workflowType, workflowOptions);
    for (WorkflowClientInterceptor i : interceptors) {
      result = i.newUntypedWorkflowStub(workflowType, workflowOptions, result);
    }
    return result;
  }

  @Override
  public WorkflowStub newUntypedWorkflowStub(
      String workflowId, Optional<String> runId, Optional<String> workflowType) {
    WorkflowExecution execution =
        WorkflowExecution.newBuilder().setWorkflowId(workflowId).setRunId(runId.orElse("")).build();
    return newUntypedWorkflowStub(execution, workflowType);
  }

  @Override
  @SuppressWarnings("deprecation")
  public WorkflowStub newUntypedWorkflowStub(
      WorkflowExecution execution, Optional<String> workflowType) {
    WorkflowStub result =
        new WorkflowStubImpl(options, workflowClientCallsInvoker, workflowType, execution);
    for (WorkflowClientInterceptor i : interceptors) {
      result = i.newUntypedWorkflowStub(execution, workflowType, result);
    }
    return result;
  }

  @Override
  public ActivityCompletionClient newActivityCompletionClient() {
    ActivityCompletionClient result =
        WorkflowThreadMarker.protectFromWorkflowThread(
            new ActivityCompletionClientImpl(
                manualActivityCompletionClientFactory, () -> {}, metricsScope),
            ActivityCompletionClient.class);
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

  @Override
  public Stream<WorkflowExecutionMetadata> listExecutions(String query, int pageSize) {
    ListWorkflowExecutionIterator iterator =
        new ListWorkflowExecutionIterator(query, options.getNamespace(), pageSize, genericClient);
    iterator.init();
    Iterator<WorkflowExecutionMetadata> wrappedIterator =
        Iterators.transform(
            iterator, info -> new WorkflowExecutionMetadata(info, options.getDataConverter()));

    // IMMUTABLE here means that "interference" (in Java Streams terms) to this spliterator is
    // impossible
    // TODO We don't add DISTINCT to be safe. It's not explicitly stated if Temporal Server list API
    // guarantees absence of duplicates
    final int CHARACTERISTICS =
        Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.DISTINCT | Spliterator.IMMUTABLE;

    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(wrappedIterator, CHARACTERISTICS), false);
  }

  public static WorkflowExecution start(Functions.Proc workflow) {
    enforceNonWorkflowThread();
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
    return start((Functions.Proc) workflow::apply);
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
    enforceNonWorkflowThread();
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
    return (CompletableFuture<R>) execute((Functions.Proc) workflow::apply);
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
