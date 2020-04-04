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

import io.temporal.common.interceptors.WorkflowCallsInterceptor;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.workflow.ExternalWorkflowStub;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/** Dynamic implementation of a strongly typed child workflow interface. */
class ExternalWorkflowInvocationHandler implements InvocationHandler {

  private final ExternalWorkflowStub stub;
  private final POJOWorkflowMetadata workflowMetadata;

  public ExternalWorkflowInvocationHandler(
      Class<?> workflowInterface,
      WorkflowExecution execution,
      WorkflowCallsInterceptor decisionContext) {
    workflowMetadata = POJOWorkflowMetadata.newForInterface(workflowInterface);
    stub = new ExternalWorkflowStubImpl(execution, decisionContext);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    // Implement StubMarker
    if (method.getName().equals(StubMarker.GET_UNTYPED_STUB_METHOD)) {
      return stub;
    }
    POJOWorkflowMetadata.MethodMetadata methodMetadata = workflowMetadata.getMethodMetadata(method);
    POJOWorkflowMetadata.WorkflowMethodType type = methodMetadata.getType();
    if (type == POJOWorkflowMetadata.WorkflowMethodType.WORKFLOW) {
      throw new IllegalStateException(
          "Cannot start a workflow with an external workflow stub "
              + "created through Workflow.newExternalWorkflowStub");
    }
    if (type == POJOWorkflowMetadata.WorkflowMethodType.QUERY) {
      throw new UnsupportedOperationException(
          "Query is not supported from workflow to workflow. "
              + "Use activity that perform the query instead.");
    }
    stub.signal(methodMetadata.getName(), args);
    return null;
  }
}
