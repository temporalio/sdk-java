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

import com.google.common.base.Defaults;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.common.metadata.WorkflowMethodType;
import io.temporal.internal.sync.StubMarker;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

/**
 * Dynamic implementation of a strongly typed workflow interface that can be used to start, signal
 * and query workflows from external processes.
 */
class WorkflowInvocationHandler implements InvocationHandler {

  public enum InvocationType {
    SYNC,
    START,
    EXECUTE,
    SIGNAL_WITH_START,
    UPDATE_WITH_START
  }

  interface SpecificInvocationHandler {
    InvocationType getInvocationType();

    void invoke(
        POJOWorkflowInterfaceMetadata workflowMetadata,
        WorkflowStub untyped,
        Method method,
        Object[] args)
        throws Throwable;

    <R> R getResult(Class<R> resultClass);
  }

  private static final ThreadLocal<SpecificInvocationHandler> invocationContext =
      new ThreadLocal<>();

  /** Must call {@link #closeAsyncInvocation()} if this one was called. */
  static void initAsyncInvocation(InvocationType type) {
    initAsyncInvocation(type, null);
  }

  /** Must call {@link #closeAsyncInvocation()} if this one was called. */
  static <T> void initAsyncInvocation(InvocationType type, T value) {
    if (invocationContext.get() != null) {
      throw new IllegalStateException("already in start invocation");
    }
    if (type == InvocationType.START) {
      invocationContext.set(new StartWorkflowInvocationHandler());
    } else if (type == InvocationType.EXECUTE) {
      invocationContext.set(new ExecuteWorkflowInvocationHandler());
    } else if (type == InvocationType.SIGNAL_WITH_START) {
      SignalWithStartBatchRequest batch = (SignalWithStartBatchRequest) value;
      invocationContext.set(new SignalWithStartWorkflowInvocationHandler(batch));
    } else if (type == InvocationType.UPDATE_WITH_START) {
      WorkflowStartOperationUpdate operation = (WorkflowStartOperationUpdate) value;
      invocationContext.set(new UpdateWithStartInvocationHandler(operation));
    } else {
      throw new IllegalArgumentException("Unexpected InvocationType: " + type);
    }
  }

  static <R> R getAsyncInvocationResult(Class<R> resultClass) {
    SpecificInvocationHandler invocation = invocationContext.get();
    if (invocation == null) {
      throw new IllegalStateException("initAsyncInvocation wasn't called");
    }
    return invocation.getResult(resultClass);
  }

  /** Closes async invocation created through {@link #initAsyncInvocation(InvocationType)} */
  static void closeAsyncInvocation() {
    invocationContext.remove();
  }

  private final WorkflowStub untyped;
  private final POJOWorkflowInterfaceMetadata workflowMetadata;

  @SuppressWarnings("deprecation")
  WorkflowInvocationHandler(
      Class<?> workflowInterface,
      WorkflowClientOptions clientOptions,
      WorkflowClientCallsInterceptor workflowClientCallsInvoker,
      WorkflowExecution execution) {
    workflowMetadata = POJOWorkflowInterfaceMetadata.newInstance(workflowInterface, false);
    Optional<String> workflowType = workflowMetadata.getWorkflowType();
    WorkflowStub stub =
        new WorkflowStubImpl(clientOptions, workflowClientCallsInvoker, workflowType, execution);
    for (WorkflowClientInterceptor i : clientOptions.getInterceptors()) {
      stub = i.newUntypedWorkflowStub(execution, workflowType, stub);
    }
    this.untyped = stub;
  }

  @SuppressWarnings("deprecation")
  WorkflowInvocationHandler(
      Class<?> workflowInterface,
      WorkflowClientOptions clientOptions,
      WorkflowClientCallsInterceptor workflowClientCallsInvoker,
      WorkflowOptions options) {
    Objects.requireNonNull(options, "options");
    workflowMetadata = POJOWorkflowInterfaceMetadata.newInstance(workflowInterface);
    Optional<POJOWorkflowMethodMetadata> workflowMethodMetadata =
        workflowMetadata.getWorkflowMethod();
    if (!workflowMethodMetadata.isPresent()) {
      throw new IllegalArgumentException(
          "Method annotated with @WorkflowMethod is not found in " + workflowInterface);
    }
    Method workflowMethod = workflowMethodMetadata.get().getWorkflowMethod();
    MethodRetry methodRetry = workflowMethod.getAnnotation(MethodRetry.class);
    CronSchedule cronSchedule = workflowMethod.getAnnotation(CronSchedule.class);
    WorkflowOptions mergedOptions = WorkflowOptions.merge(methodRetry, cronSchedule, options);
    String workflowType = workflowMethodMetadata.get().getName();
    WorkflowStub stub =
        new WorkflowStubImpl(
            clientOptions, workflowClientCallsInvoker, workflowType, mergedOptions);
    for (WorkflowClientInterceptor i : clientOptions.getInterceptors()) {
      stub = i.newUntypedWorkflowStub(workflowType, mergedOptions, stub);
    }
    this.untyped = stub;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      if (method.equals(Object.class.getMethod("toString"))) {
        // TODO: workflow info
        return "WorkflowInvocationHandler";
      }
    } catch (NoSuchMethodException e) {
      throw new Error("unexpected", e);
    }
    // Implement StubMarker
    if (method.getName().equals(StubMarker.GET_UNTYPED_STUB_METHOD)) {
      return untyped;
    }
    if (!method.getDeclaringClass().isInterface()) {
      throw new IllegalArgumentException(
          "Interface type is expected: " + method.getDeclaringClass());
    }
    SpecificInvocationHandler handler = invocationContext.get();
    if (handler == null) {
      handler = new SyncWorkflowInvocationHandler();
    }
    handler.invoke(this.workflowMetadata, untyped, method, args);
    if (handler.getInvocationType() == InvocationType.SYNC) {
      return handler.getResult(method.getReturnType());
    }
    return Defaults.defaultValue(method.getReturnType());
  }

  private static void startWorkflow(WorkflowStub untyped, Object[] args) {
    Optional<WorkflowOptions> options = untyped.getOptions();
    if (untyped.getExecution() == null
        || (options.isPresent()
            && options.get().getWorkflowIdReusePolicy()
                == WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)) {
      try {
        untyped.start(args);
      } catch (WorkflowExecutionAlreadyStarted e) {
        // We do allow duplicated calls if policy is not AllowDuplicate. Semantic is to wait for
        // result.
        if (options.isPresent()
            && options.get().getWorkflowIdReusePolicy()
                == WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE) {
          throw e;
        }
      }
    }
  }

  static void checkAnnotations(
      Method method,
      WorkflowMethod workflowMethod,
      QueryMethod queryMethod,
      SignalMethod signalMethod) {
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
  }

  private static class StartWorkflowInvocationHandler implements SpecificInvocationHandler {

    private Object result;

    @Override
    public InvocationType getInvocationType() {
      return InvocationType.START;
    }

    @Override
    public void invoke(
        POJOWorkflowInterfaceMetadata workflowMetadata,
        WorkflowStub untyped,
        Method method,
        Object[] args) {
      WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
      if (workflowMethod == null) {
        throw new IllegalArgumentException(
            "WorkflowClient.start can be called only on a method annotated with @WorkflowMethod");
      }
      result = untyped.start(args);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R getResult(Class<R> resultClass) {
      return (R) result;
    }
  }

  private static class SyncWorkflowInvocationHandler implements SpecificInvocationHandler {

    private Object result;

    @Override
    public InvocationType getInvocationType() {
      return InvocationType.SYNC;
    }

    @Override
    public void invoke(
        POJOWorkflowInterfaceMetadata workflowMetadata,
        WorkflowStub untyped,
        Method method,
        Object[] args) {
      POJOWorkflowMethodMetadata methodMetadata = workflowMetadata.getMethodMetadata(method);
      WorkflowMethodType type = methodMetadata.getType();
      if (type == WorkflowMethodType.WORKFLOW) {
        result = startWorkflow(untyped, method, args);
      } else if (type == WorkflowMethodType.QUERY) {
        result = queryWorkflow(methodMetadata, untyped, method, args);
      } else if (type == WorkflowMethodType.SIGNAL) {
        signalWorkflow(methodMetadata, untyped, method, args);
        result = null;
      } else if (type == WorkflowMethodType.UPDATE) {
        result = updateWorkflow(methodMetadata, untyped, method, args);
      } else {
        throw new IllegalArgumentException(
            method + " is not annotated with @WorkflowMethod, @QueryMethod, @UpdateMethod");
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R getResult(Class<R> resultClass) {
      return (R) result;
    }

    private void signalWorkflow(
        POJOWorkflowMethodMetadata methodMetadata,
        WorkflowStub untyped,
        Method method,
        Object[] args) {
      if (method.getReturnType() != Void.TYPE) {
        throw new IllegalArgumentException("Signal method must have void return type: " + method);
      }
      String signalName = methodMetadata.getName();
      untyped.signal(signalName, args);
    }

    private Object queryWorkflow(
        POJOWorkflowMethodMetadata methodMetadata,
        WorkflowStub untyped,
        Method method,
        Object[] args) {
      if (method.getReturnType() == Void.TYPE) {
        throw new IllegalArgumentException("Query method cannot have void return type: " + method);
      }
      String queryType = methodMetadata.getName();
      return untyped.query(queryType, method.getReturnType(), method.getGenericReturnType(), args);
    }

    private Object updateWorkflow(
        POJOWorkflowMethodMetadata methodMetadata,
        WorkflowStub untyped,
        Method method,
        Object[] args) {
      String updateType = methodMetadata.getName();
      return untyped.update(updateType, method.getReturnType(), args);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private Object startWorkflow(WorkflowStub untyped, Method method, Object[] args) {
      WorkflowInvocationHandler.startWorkflow(untyped, args);
      return untyped.getResult(method.getReturnType(), method.getGenericReturnType());
    }
  }

  private static class ExecuteWorkflowInvocationHandler implements SpecificInvocationHandler {

    private Object result;

    @Override
    public InvocationType getInvocationType() {
      return InvocationType.EXECUTE;
    }

    @Override
    public void invoke(
        POJOWorkflowInterfaceMetadata workflowMetadata,
        WorkflowStub untyped,
        Method method,
        Object[] args) {
      WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
      if (workflowMethod == null) {
        throw new IllegalArgumentException(
            "WorkflowClient.execute can be called only on a method annotated with @WorkflowMethod");
      }
      WorkflowInvocationHandler.startWorkflow(untyped, args);
      result = untyped.getResultAsync(method.getReturnType(), method.getGenericReturnType());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> R getResult(Class<R> resultClass) {
      return (R) result;
    }
  }

  private static class SignalWithStartWorkflowInvocationHandler
      implements SpecificInvocationHandler {

    private final SignalWithStartBatchRequest batch;

    public SignalWithStartWorkflowInvocationHandler(SignalWithStartBatchRequest batch) {
      this.batch = batch;
    }

    @Override
    public InvocationType getInvocationType() {
      return InvocationType.SIGNAL_WITH_START;
    }

    @Override
    public void invoke(
        POJOWorkflowInterfaceMetadata workflowMetadata,
        WorkflowStub untyped,
        Method method,
        Object[] args) {
      POJOWorkflowMethodMetadata methodMetadata = workflowMetadata.getMethodMetadata(method);
      switch (methodMetadata.getType()) {
        case QUERY:
          throw new IllegalArgumentException(
              "SignalWithStart batch doesn't accept methods annotated with @QueryMethod");
        case WORKFLOW:
          batch.start(untyped, args);
          break;
        case SIGNAL:
          batch.signal(untyped, methodMetadata.getName(), args);
          break;
      }
    }

    @Override
    public <R> R getResult(Class<R> resultClass) {
      throw new IllegalStateException("No result is expected");
    }
  }

  private static class UpdateWithStartInvocationHandler implements SpecificInvocationHandler {

    private int step;

    private final WorkflowStartOperationUpdate operation;

    public UpdateWithStartInvocationHandler(WorkflowStartOperationUpdate operation) {
      this.operation = operation;
    }

    @Override
    public InvocationType getInvocationType() {
      return InvocationType.UPDATE_WITH_START;
    }

    @Override
    public void invoke(
        POJOWorkflowInterfaceMetadata workflowMetadata,
        WorkflowStub untyped,
        Method method,
        Object[] args) {

      step += 1;
      POJOWorkflowMethodMetadata methodMetadata = workflowMetadata.getMethodMetadata(method);

      // first call
      if (step == 1) {
        UpdateMethod updateMethod = method.getAnnotation(UpdateMethod.class);
        if (updateMethod == null) {
          throw new IllegalArgumentException(
              "Method '" + method.getName() + "' is not an UpdateMethod");
        }
        this.operation.prepareUpdate(
            untyped,
            methodMetadata.getName(),
            method.getReturnType(),
            method.getGenericReturnType(),
            args);
        return;
      }

      // second call
      if (step == 2) {
        WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
        if (workflowMethod == null) {
          throw new IllegalArgumentException(
              "Method '" + method.getName() + "' is not a WorkflowMethod");
        }
        this.operation.prepareStart(untyped);
        return;
      }

      throw new IllegalArgumentException("UpdateWithStartInvocationHandler called too many times");
    }

    @Override
    public <R> R getResult(Class<R> resultClass) {
      throw new IllegalStateException("No result is expected");
    }
  }
}
