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

import com.google.common.annotations.VisibleForTesting;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.common.MethodRetry;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.ActivityStub;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@VisibleForTesting
public class LocalActivityInvocationHandler extends ActivityInvocationHandlerBase {
  private final LocalActivityOptions options;
  private final Map<String, LocalActivityOptions> activityMethodOptions;
  private final WorkflowOutboundCallsInterceptor activityExecutor;

  @VisibleForTesting
  public static InvocationHandler newInstance(
      Class<?> activityInterface,
      LocalActivityOptions options,
      Map<String, LocalActivityOptions> methodOptions,
      WorkflowOutboundCallsInterceptor activityExecutor) {
    return new LocalActivityInvocationHandler(
        activityInterface, activityExecutor, options, methodOptions);
  }

  private LocalActivityInvocationHandler(
      Class<?> activityInterface,
      WorkflowOutboundCallsInterceptor activityExecutor,
      LocalActivityOptions options,
      Map<String, LocalActivityOptions> methodOptions) {
    this.options = options;
    this.activityMethodOptions = (methodOptions == null) ? new HashMap<>() : methodOptions;
    this.activityExecutor = activityExecutor;
    init(activityInterface);
  }

  @VisibleForTesting
  @Override
  public Function<Object[], Object> getActivityFunc(
      Method method, MethodRetry methodRetry, String activityName) {
    Function<Object[], Object> function;
    LocalActivityOptions mergedOptions =
        LocalActivityOptions.newBuilder(options)
            .mergeActivityOptions(activityMethodOptions.get(activityName))
            .setMethodRetry(methodRetry)
            .build();
    ActivityStub stub = LocalActivityStubImpl.newInstance(mergedOptions, activityExecutor);
    function =
        (a) -> stub.execute(activityName, method.getReturnType(), method.getGenericReturnType(), a);
    return function;
  }
}
