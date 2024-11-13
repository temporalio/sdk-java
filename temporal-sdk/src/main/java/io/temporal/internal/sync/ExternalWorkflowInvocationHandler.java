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

package io.temporal.internal.sync;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.workflow.ExternalWorkflowStub;
import io.temporal.workflow.Functions;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/** Dynamic implementation of a strongly typed child workflow interface. */
class ExternalWorkflowInvocationHandler implements InvocationHandler {

  private final ExternalWorkflowStub stub;
  private final POJOWorkflowInterfaceMetadata workflowMetadata;

  public ExternalWorkflowInvocationHandler(
      Class<?> workflowInterface,
      WorkflowExecution execution,
      WorkflowOutboundCallsInterceptor workflowOutboundCallsInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    this.workflowMetadata = POJOWorkflowInterfaceMetadata.newInstance(workflowInterface);
    this.stub =
        new ExternalWorkflowStubImpl(execution, workflowOutboundCallsInterceptor, assertReadOnly);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    // Proxy the toString method so the stub can be inspected when debugging.
    try {
      if (method.equals(Object.class.getMethod("toString"))) {
        return proxyToString();
      }
    } catch (NoSuchMethodException e) {
      throw new Error("unexpected", e);
    }
    // Implement StubMarker
    if (method.getName().equals(StubMarker.GET_UNTYPED_STUB_METHOD)) {
      return stub;
    }
    POJOWorkflowMethodMetadata methodMetadata = workflowMetadata.getMethodMetadata(method);
    switch (methodMetadata.getType()) {
      case QUERY:
        throw new UnsupportedOperationException(
            "Query is not supported from workflow to workflow. "
                + "Use activity that perform the query instead.");
      case WORKFLOW:
        throw new IllegalStateException(
            "Cannot start a workflow with an external workflow stub "
                + "created through Workflow.newExternalWorkflowStub");
      case SIGNAL:
        stub.signal(methodMetadata.getName(), args);
        break;
      case UPDATE:
        throw new UnsupportedOperationException(
            "Cannot update a workflow with an external workflow stub "
                + "created through Workflow.newExternalWorkflowStub");
      default:
        throw new IllegalStateException("unreachable");
    }
    return null;
  }

  private String proxyToString() {
    return "ExternalWorkflowProxy{"
        + "workflowType='"
        + workflowMetadata.getWorkflowType().orElse("")
        + '\''
        + ", execution="
        + stub.getExecution()
        + '}';
  }
}
