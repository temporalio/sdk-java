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

import static io.temporal.internal.common.InternalUtils.getValueOrDefault;

import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.common.metadata.POJOWorkflowMethod;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.common.metadata.WorkflowMethodType;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ChildWorkflowStub;
import io.temporal.workflow.Functions;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Optional;

/** Dynamic implementation of a strongly typed child workflow interface. */
class ChildWorkflowInvocationHandler implements InvocationHandler {

  private final ChildWorkflowStub stub;
  private final POJOWorkflowInterfaceMetadata workflowMetadata;

  ChildWorkflowInvocationHandler(
      Class<?> workflowInterface,
      ChildWorkflowOptions options,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    workflowMetadata = POJOWorkflowInterfaceMetadata.newInstance(workflowInterface);
    Optional<POJOWorkflowMethodMetadata> workflowMethodMetadata =
        workflowMetadata.getWorkflowMethod();
    if (!workflowMethodMetadata.isPresent()) {
      throw new IllegalArgumentException(
          "Missing method annotated with @WorkflowMethod: " + workflowInterface.getName());
    }
    POJOWorkflowMethod workflowMethod = workflowMethodMetadata.get().getWorkflowMethod();
    ChildWorkflowOptions merged =
        ChildWorkflowOptions.newBuilder(options)
            .setMethodRetry(workflowMethod.getRetryAnnotation())
            .setCronSchedule(workflowMethod.getChronScheduleAnnotation())
            .validateAndBuildWithDefaults();
    this.stub =
        new ChildWorkflowStubImpl(
            workflowMethodMetadata.get().getName(),
            merged,
            outboundCallsInterceptor,
            assertReadOnly);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    // Implement StubMarker
    if (method.getName().equals(StubMarker.GET_UNTYPED_STUB_METHOD)) {
      return stub;
    }
    POJOWorkflowMethodMetadata methodMetadata = workflowMetadata.getMethodMetadata(method);
    WorkflowMethodType type = methodMetadata.getType();

    if (type == WorkflowMethodType.WORKFLOW) {
      return getValueOrDefault(
          stub.execute(method.getReturnType(), method.getGenericReturnType(), args),
          method.getReturnType());
    }
    if (type == WorkflowMethodType.SIGNAL) {
      stub.signal(methodMetadata.getName(), args);
      return null;
    }
    if (type == WorkflowMethodType.QUERY) {
      throw new UnsupportedOperationException(
          "Query is not supported from workflow to workflow. "
              + "Use an activity that performs the query instead.");
    }
    if (type == WorkflowMethodType.UPDATE) {
      throw new UnsupportedOperationException(
          "Update is not supported from workflow to workflow. "
              + "Use an activity that performs the update instead.");
    }
    throw new IllegalArgumentException("unreachable");
  }
}
