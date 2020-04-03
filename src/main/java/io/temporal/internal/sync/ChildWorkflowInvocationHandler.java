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

import static io.temporal.internal.common.InternalUtils.getSimpleName;
import static io.temporal.internal.common.InternalUtils.getValueOrDefault;
import static io.temporal.internal.sync.WorkflowInvocationHandler.initMethodToNameMap;

import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.common.interceptors.WorkflowCallsInterceptor;
import io.temporal.internal.common.InternalUtils;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ChildWorkflowStub;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Dynamic implementation of a strongly typed child workflow interface. */
class ChildWorkflowInvocationHandler implements InvocationHandler {

  private final ChildWorkflowStub stub;
  private final Map<Method, String> methodToNameMap = new HashMap<>();
  private final String workflowType;

  ChildWorkflowInvocationHandler(
      Class<?> workflowInterface,
      ChildWorkflowOptions options,
      WorkflowCallsInterceptor decisionContext) {
    InternalUtils.MethodInterfacePair workflowMethodPair =
        initMethodToNameMap(workflowInterface, methodToNameMap);
    Method workflowMethod = workflowMethodPair.getMethod();
    MethodRetry retryAnnotation = workflowMethod.getAnnotation(MethodRetry.class);
    CronSchedule cronSchedule = workflowMethod.getAnnotation(CronSchedule.class);
    WorkflowMethod workflowAnnotation = workflowMethod.getAnnotation(WorkflowMethod.class);
    if (workflowAnnotation.name().isEmpty()) {
      workflowType = getSimpleName(workflowMethodPair);
    } else {
      workflowType = workflowAnnotation.name();
    }
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
    // Implement StubMarker
    if (method.getName().equals(StubMarker.GET_UNTYPED_STUB_METHOD)) {
      return stub;
    }
    String name = methodToNameMap.get(method);
    if (name == null) {
      throw new IllegalArgumentException("Unknown method: " + method.getName());
    }
    if (!name.equals(workflowType)) {
      throw new IllegalArgumentException(
          "Workflow type doesn't match: " + workflowType + "!=" + name);
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
      signalWorkflow(name, args);
      return null;
    }
    throw new IllegalArgumentException(
        method + " is not annotated with @WorkflowMethod or @QueryMethod");
  }

  private void signalWorkflow(String signalName, Object[] args) {
    stub.signal(signalName, args);
  }
}
