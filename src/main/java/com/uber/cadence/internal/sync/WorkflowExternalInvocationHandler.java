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

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.client.DuplicateWorkflowException;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.internal.external.GenericWorkflowClientExternal;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.WorkflowMethod;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WorkflowExternalInvocationHandler implements InvocationHandler {

  private static final Logger log =
      LoggerFactory.getLogger(WorkflowExternalInvocationHandler.class);

  public enum InvocationType {
    START,
    EXECUTE
  }

  private static class AsyncInvocation {

    private final InvocationType type;
    // Holds either WorkflowExecution or CompletableFuture to workflow result.
    private final AtomicReference<Object> result = new AtomicReference<>();

    public AsyncInvocation(InvocationType type) {
      this.type = type;
    }
  }

  private static final ThreadLocal<AsyncInvocation> asyncInvocation = new ThreadLocal<>();
  private final AtomicReference<UntypedWorkflowStubImpl> untyped = new AtomicReference<>();
  private final WorkflowOptions options;
  private final DataConverter dataConverter;
  private final GenericWorkflowClientExternal genericClient;
  private String workflowType;

  /** Must call {@link #closeAsyncInvocation()} if this one was called. */
  public static void initAsyncInvocation(InvocationType type) {
    if (asyncInvocation.get() != null) {
      throw new IllegalStateException("already in start invocation");
    }
    asyncInvocation.set(new AsyncInvocation(type));
  }

  @SuppressWarnings("unchecked")
  public static <R> R getAsyncInvocationResult(Class<R> resultClass) {
    AsyncInvocation reference = asyncInvocation.get();
    if (reference == null) {
      throw new IllegalStateException("initAsyncInvocation wasn't called");
    }
    Object result = reference.result.get();
    if (result == null) {
      throw new IllegalStateException(
          "Only methods of a stub created through WorkflowClient.newWorkflowStub "
              + "can be used as a parameter to the start.");
    }
    if (reference.type == InvocationType.START) {
      if (!resultClass.equals(WorkflowExecution.class)) {
        throw new IllegalArgumentException(
            "Only WorkflowExecution type is allowed with START " + "InvocationThype");
      }
      return (R) result;
    }
    if (reference.type == InvocationType.EXECUTE) {
      if (!resultClass.isAssignableFrom(result.getClass())) {
        throw new IllegalArgumentException(
            "Result type \""
                + result.getClass().getName()
                + "\" "
                + "doesn't match expected type \""
                + resultClass.getName()
                + "\"");
      }
      return (R) result;
    }
    throw new Error("Unknown invocation type: " + reference.type);
  }

  /** Closes async invocation created through {@link #initAsyncInvocation(InvocationType)} */
  public static void closeAsyncInvocation() {
    asyncInvocation.remove();
  }

  WorkflowExternalInvocationHandler(
      GenericWorkflowClientExternal genericClient,
      WorkflowExecution execution,
      DataConverter dataConverter) {
    if (execution == null
        || execution.getWorkflowId() == null
        || execution.getWorkflowId().isEmpty()) {
      throw new IllegalArgumentException("null or empty workflowId");
    }
    this.genericClient = genericClient;
    this.untyped.set(new UntypedWorkflowStubImpl(genericClient, dataConverter, execution));
    this.options = null;
    this.dataConverter = dataConverter;
  }

  WorkflowExternalInvocationHandler(
      GenericWorkflowClientExternal genericClient,
      WorkflowOptions options,
      DataConverter dataConverter) {
    this.genericClient = genericClient;
    this.options = options;
    this.dataConverter = dataConverter;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
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
      WorkflowOptions mergedOptions = WorkflowOptions.merge(workflowMethod, options);
      // We do allow duplicated calls if policy is not AllowDuplicate. Semantic is to wait for result.
      UntypedWorkflowStubImpl workflowStub = untyped.get();
      if (workflowStub != null) { // stub is reused
        if (mergedOptions.getWorkflowIdReusePolicy() == WorkflowIdReusePolicy.AllowDuplicate
            || (workflowType != null
                && !workflowType.equals(getWorkflowType(method, workflowMethod)))) {
          throw new DuplicateWorkflowException(
              workflowStub.getExecution(),
              workflowType,
              "Cannot call @WorkflowMethod more than once per stub instance");
        }
        return workflowStub.getResult(method.getReturnType());
      }
      return startWorkflow(method, workflowMethod, mergedOptions, args);
    }
    if (queryMethod != null) {
      return queryWorkflow(method, queryMethod, args);
    }
    if (signalMethod != null) {
      signalWorkflow(method, signalMethod, args);
      return null;
    }
    throw new IllegalArgumentException(
        method + " is not annotated with @WorkflowMethod or @QueryMethod");
  }

  private void signalWorkflow(Method method, SignalMethod signalMethod, Object[] args) {
    if (method.getReturnType() != Void.TYPE) {
      throw new IllegalArgumentException("Signal method must have void return type: " + method);
    }

    String signalName = signalMethod.name();
    if (signalName.isEmpty()) {
      signalName = InternalUtils.getSimpleName(method);
    }
    getUntyped().signal(signalName, args);
  }

  private UntypedWorkflowStubImpl getUntyped() {
    UntypedWorkflowStubImpl result = untyped.get();
    if (result == null) {
      throw new IllegalStateException("Not started yet");
    }
    return result;
  }

  private Object queryWorkflow(Method method, QueryMethod queryMethod, Object[] args) {
    if (method.getReturnType() == Void.TYPE) {
      throw new IllegalArgumentException("Query method cannot have void return type: " + method);
    }
    String queryType = queryMethod.name();
    if (queryType.isEmpty()) {
      queryType = InternalUtils.getSimpleName(method);
    }

    return getUntyped().query(queryType, method.getReturnType(), args);
  }

  private Object startWorkflow(
      Method method, WorkflowMethod workflowMethod, WorkflowOptions mergedOptions, Object[] args) {
    workflowType = getWorkflowType(method, workflowMethod);
    if (log.isTraceEnabled()) {
      log.trace(
          "startWorkflow type="
              + workflowType
              + ", options="
              + mergedOptions
              + ", args="
              + Arrays.toString(args));
    }
    // There is no race condition here.
    // If it is not set then it means the invocation handler was created passing workflow type rather than execution.
    // So in worst case scenario set will be called twice with the same object.
    if (untyped.get() == null) {
      untyped.set(
          new UntypedWorkflowStubImpl(genericClient, dataConverter, workflowType, mergedOptions));
    }
    UntypedWorkflowStubImpl workflowStub = getUntyped();
    try {
      workflowStub.startWithOptions(mergedOptions, args);
    } catch (DuplicateWorkflowException e) {
      // We do allow duplicated calls if policy is not AllowDuplicate. Semantic is to wait for result.
      if (mergedOptions.getWorkflowIdReusePolicy() == WorkflowIdReusePolicy.AllowDuplicate) {
        throw e;
      }
    }
    AsyncInvocation async = asyncInvocation.get();
    if (async != null) {
      if (async.type == InvocationType.START) {
        async.result.set(workflowStub.getExecution());
      } else {
        async.result.set(untyped.get().getResultAsync(method.getReturnType()));
      }
      return null;
    }
    return workflowStub.getResult(method.getReturnType());
  }

  private static String getWorkflowType(Method method, WorkflowMethod workflowMethod) {
    String workflowName = workflowMethod.name();
    if (workflowName.isEmpty()) {
      return InternalUtils.getSimpleName(method);
    } else {
      return workflowName;
    }
  }
}
