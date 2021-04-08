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
import javax.annotation.Nonnull;

@VisibleForTesting
public class ActivityInvocationHandler extends ActivityInvocationHandlerBase {
  private final Map<Method, ActivityOptions> activityMethodOptions;
  private final WorkflowOutboundCallsInterceptor activityExecutor;

  @VisibleForTesting
  public static InvocationHandler newInstance(
      @Nonnull Class<?> activityInterface,
      @Nonnull ActivityOptions options,
      @Nonnull WorkflowOutboundCallsInterceptor activityExecutor) {
    return new ActivityInvocationHandler(activityInterface, activityExecutor, options, null);
  }

  @VisibleForTesting
  public static InvocationHandler newInstance(
      @Nonnull Class<?> activityInterface,
      @Nonnull ActivityOptions options,
      @Nonnull Map<String, ActivityOptions> methodOptions,
      @Nonnull WorkflowOutboundCallsInterceptor activityExecutor) {
    return new ActivityInvocationHandler(
        activityInterface, activityExecutor, options, methodOptions);
  }

  private ActivityInvocationHandler(
      Class<?> activityInterface,
      WorkflowOutboundCallsInterceptor activityExecutor,
      ActivityOptions options,
      Map<String, ActivityOptions> methodOptions) {
    this.activityMethodOptions = new HashMap<>();
    if (methodOptions == null) {
      for (Method method : activityInterface.getMethods()) {
        this.activityMethodOptions.put(method, options);
      }
    } else {
      for (Method method : activityInterface.getMethods()) {
        ActivityOptions mergedOptions =
            ActivityOptions.newBuilder(options)
                .mergeMethodOptions(methodOptions.get(method.getName()))
                .build();
        this.activityMethodOptions.put(method, mergedOptions);
      }
    }
    this.activityExecutor = activityExecutor;
    init(activityInterface);
  }

  @Override
  protected Function<Object[], Object> getActivityFunc(
      Method method, MethodRetry methodRetry, String activityName) {
    Function<Object[], Object> function;
    ActivityOptions options = this.activityMethodOptions.get(method);
    ActivityOptions mergedOptions =
        ActivityOptions.newBuilder(options).mergeMethodRetry(methodRetry).build();
    ActivityStub stub = ActivityStubImpl.newInstance(mergedOptions, activityExecutor);
    function =
        (a) -> stub.execute(activityName, method.getReturnType(), method.getGenericReturnType(), a);
    return function;
  }
}
