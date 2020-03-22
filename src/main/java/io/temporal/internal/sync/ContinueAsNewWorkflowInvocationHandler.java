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

import io.temporal.internal.common.InternalUtils;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowCallsInterceptor;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Optional;

class ContinueAsNewWorkflowInvocationHandler implements InvocationHandler {

  private final ContinueAsNewOptions options;
  private final WorkflowCallsInterceptor decisionContext;

  ContinueAsNewWorkflowInvocationHandler(
      ContinueAsNewOptions options, WorkflowCallsInterceptor decisionContext) {
    this.options = options;
    this.decisionContext = decisionContext;
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
    if (workflowMethod == null) {
      throw new IllegalStateException(
          "ContinueAsNew Stub supports only calls to methods annotated with @WorkflowMethod");
    }
    String workflowType = InternalUtils.getWorkflowType(method, workflowMethod);
    WorkflowInternal.continueAsNew(
        Optional.of(workflowType), Optional.ofNullable(options), args, decisionContext);
    return getValueOrDefault(null, method.getReturnType());
  }
}
