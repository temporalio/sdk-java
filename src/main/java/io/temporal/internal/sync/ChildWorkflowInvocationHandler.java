/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Modifications copyright (C) 2020 Temporal Technologies, Inc.
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

import static io.temporal.internal.common.InternalUtils.getValueOrDefault;
import static io.temporal.internal.common.InternalUtils.getWorkflowMethod;
import static io.temporal.internal.common.InternalUtils.getWorkflowType;

import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.internal.common.InternalUtils;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ChildWorkflowStub;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterceptor;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/** Dynamic implementation of a strongly typed child workflow interface. */
class ChildWorkflowInvocationHandler implements InvocationHandler {

  private final ChildWorkflowStub stub;

  ChildWorkflowInvocationHandler(
      Class<?> workflowInterface,
      ChildWorkflowOptions options,
      WorkflowInterceptor decisionContext) {
    Method workflowMethod = getWorkflowMethod(workflowInterface);
    WorkflowMethod workflowAnnotation = workflowMethod.getAnnotation(WorkflowMethod.class);
    String workflowType = getWorkflowType(workflowMethod, workflowAnnotation);
    MethodRetry retryAnnotation = workflowMethod.getAnnotation(MethodRetry.class);
    CronSchedule cronSchedule = workflowMethod.getAnnotation(CronSchedule.class);

    ChildWorkflowOptions merged =
        ChildWorkflowOptions.newBuilder(options)
            .setWorkflowMethod(workflowAnnotation)
            .setMethodRetry(retryAnnotation)
            .setCronSchedule(cronSchedule)
            .validateAndBuildWithDefaults();
    this.stub = new ChildWorkflowStubImpl(workflowType, merged, decisionContext);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    // Implement WorkflowStub
    if (method.getName().equals(WorkflowStubMarker.GET_EXECUTION_METHOD_NAME)) {
      return stub.getExecution();
    }
    WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
    QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
    SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
    WorkflowInvocationHandler.checkAnnotations(method, workflowMethod, queryMethod, signalMethod);
    if (workflowMethod != null) {
      return getValueOrDefault(
          stub.execute(method.getReturnType(), method.getGenericReturnType(), args),
          method.getReturnType());
    }
    if (queryMethod != null) {
      throw new UnsupportedOperationException(
          "Query is not supported from workflow to workflow. "
              + "Use activity that perform the query instead.");
    }
    if (signalMethod != null) {
      signalWorkflow(method, signalMethod, args);
      return null;
    }
    throw new IllegalArgumentException(
        method + " is not annotated with @WorkflowMethod or @QueryMethod");
  }

  private void signalWorkflow(Method method, SignalMethod signalMethod, Object[] args) {
    String signalName = signalMethod.name();
    if (signalName.isEmpty()) {
      signalName = InternalUtils.getSimpleName(method);
    }
    stub.signal(signalName, args);
  }
}
