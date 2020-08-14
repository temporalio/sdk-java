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

import static io.temporal.internal.common.InternalUtils.getValueOrDefault;

import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ChildWorkflowStub;
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
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor) {
    workflowMetadata = POJOWorkflowInterfaceMetadata.newInstance(workflowInterface);
    Optional<POJOWorkflowMethodMetadata> workflowMethodMetadata =
        workflowMetadata.getWorkflowMethod();
    if (!workflowMethodMetadata.isPresent()) {
      throw new IllegalArgumentException(
          "Missing method annotated with @WorkflowMethod: " + workflowInterface.getName());
    }
    Method workflowMethod = workflowMethodMetadata.get().getWorkflowMethod();
    MethodRetry retryAnnotation = workflowMethod.getAnnotation(MethodRetry.class);
    CronSchedule cronSchedule = workflowMethod.getAnnotation(CronSchedule.class);
    ChildWorkflowOptions merged =
        ChildWorkflowOptions.newBuilder(options)
            .setMethodRetry(retryAnnotation)
            .setCronSchedule(cronSchedule)
            .validateAndBuildWithDefaults();
    this.stub =
        new ChildWorkflowStubImpl(
            workflowMethodMetadata.get().getName(), merged, outboundCallsInterceptor);
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
              + "Use activity that perform the query instead.");
    }
    throw new IllegalArgumentException("unreachable");
  }
}
