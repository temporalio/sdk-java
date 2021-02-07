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

import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.workflow.ContinueAsNewOptions;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Optional;

class ContinueAsNewWorkflowInvocationHandler implements InvocationHandler {

  private final ContinueAsNewOptions options;
  private final WorkflowOutboundCallsInterceptor outboundCallsInterceptor;
  private final POJOWorkflowInterfaceMetadata workflowMetadata;

  ContinueAsNewWorkflowInvocationHandler(
      Class<?> interfaceClass,
      ContinueAsNewOptions options,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor) {
    workflowMetadata = POJOWorkflowInterfaceMetadata.newInstance(interfaceClass);
    if (!workflowMetadata.getWorkflowMethod().isPresent()) {
      throw new IllegalArgumentException(
          "Missing method annotated with @WorkflowMethod: " + interfaceClass.getName());
    }
    this.options = options;
    this.outboundCallsInterceptor = outboundCallsInterceptor;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) {
    String workflowType = workflowMetadata.getMethodMetadata(method).getName();
    WorkflowInternal.continueAsNew(
        Optional.of(workflowType), Optional.ofNullable(options), args, outboundCallsInterceptor);
    return getValueOrDefault(null, method.getReturnType());
  }
}
