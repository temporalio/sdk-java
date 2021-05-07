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
import io.temporal.activity.ActivityOptions;
import io.temporal.common.MethodRetry;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.ActivityStub;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@VisibleForTesting
public class ActivityInvocationHandler extends ActivityInvocationHandlerBase {
  private final ActivityOptions options;
  private final Map<String, ActivityOptions> activityMethodOptions;
  private final WorkflowOutboundCallsInterceptor activityExecutor;

  @VisibleForTesting
  public static InvocationHandler newInstance(
      Class<?> activityInterface,
      ActivityOptions options,
      Map<String, ActivityOptions> methodOptions,
      WorkflowOutboundCallsInterceptor activityExecutor) {
    return new ActivityInvocationHandler(
        activityInterface, activityExecutor, options, methodOptions);
  }

  private ActivityInvocationHandler(
      Class<?> activityInterface,
      WorkflowOutboundCallsInterceptor activityExecutor,
      ActivityOptions options,
      Map<String, ActivityOptions> methodOptions) {
    this.options = options;
    this.activityMethodOptions = (methodOptions == null) ? new HashMap<>() : methodOptions;
    this.activityExecutor = activityExecutor;
    init(activityInterface);
  }

  @Override
  protected Function<Object[], Object> getActivityFunc(
      Method method, MethodRetry methodRetry, String activityName) {
    Function<Object[], Object> function;
    ActivityOptions merged =
        ActivityOptions.newBuilder(options)
            .mergeActivityOptions(this.activityMethodOptions.get(activityName))
            .mergeMethodRetry(methodRetry)
            .build();
    if (merged.getStartToCloseTimeout() == null && merged.getScheduleToCloseTimeout() == null) {
      throw new IllegalArgumentException(
          "Both StartToCloseTimeout and ScheduleToCloseTimeout aren't specified for "
              + activityName
              + " activity. Please set at least one of the above through the ActivityStub or WorkflowImplementationOptions.");
    }
    ActivityStub stub = ActivityStubImpl.newInstance(merged, activityExecutor);
    function =
        (a) -> stub.execute(activityName, method.getReturnType(), method.getGenericReturnType(), a);
    return function;
  }
}
