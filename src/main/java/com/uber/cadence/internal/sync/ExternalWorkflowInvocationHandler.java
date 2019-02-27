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

import static com.uber.cadence.internal.common.InternalUtils.getValueOrDefault;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.workflow.ExternalWorkflowStub;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.WorkflowInterceptor;
import com.uber.cadence.workflow.WorkflowMethod;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/** Dynamic implementation of a strongly typed child workflow interface. */
class ExternalWorkflowInvocationHandler implements InvocationHandler {

  private final ExternalWorkflowStub stub;

  public ExternalWorkflowInvocationHandler(
      WorkflowExecution execution, WorkflowInterceptor decisionContext) {
    stub = new ExternalWorkflowStubImpl(execution, decisionContext);
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
      throw new IllegalStateException(
          "Cannot start a workflow with an external workflow stub "
              + "created through Workflow.newExternalWorkflowStub");
    }
    if (queryMethod != null) {
      return getValueOrDefault(queryWorkflow(method, queryMethod, args), method.getReturnType());
    }
    if (signalMethod != null) {
      signalWorkflow(method, signalMethod, args);
      return null;
    }
    throw new IllegalArgumentException(
        method + " is not annotated with @SignalMethod or @QueryMethod");
  }

  private void signalWorkflow(Method method, SignalMethod signalMethod, Object[] args) {
    String signalName = signalMethod.name();
    if (signalName.isEmpty()) {
      signalName = InternalUtils.getSimpleName(method);
    }
    stub.signal(signalName, args);
  }

  @SuppressWarnings("unused")
  private Object queryWorkflow(Method method, QueryMethod queryMethod, Object[] args) {
    throw new UnsupportedOperationException(
        "Query is not supported from workflow to workflow. "
            + "Use activity that perform the query instead.");
  }
}
