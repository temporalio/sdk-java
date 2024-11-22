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

import com.google.common.annotations.VisibleForTesting;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.MethodRetry;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Functions;
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
  private final Functions.Proc assertReadOnly;

  @VisibleForTesting
  public static InvocationHandler newInstance(
      Class<?> activityInterface,
      ActivityOptions options,
      Map<String, ActivityOptions> methodOptions,
      WorkflowOutboundCallsInterceptor activityExecutor,
      Functions.Proc assertReadOnly) {
    return new ActivityInvocationHandler(
        activityInterface, activityExecutor, options, methodOptions, assertReadOnly);
  }

  private ActivityInvocationHandler(
      Class<?> activityInterface,
      WorkflowOutboundCallsInterceptor activityExecutor,
      ActivityOptions options,
      Map<String, ActivityOptions> methodOptions,
      Functions.Proc assertReadOnly) {
    super(activityInterface);
    this.options = options;
    this.activityMethodOptions = (methodOptions == null) ? new HashMap<>() : methodOptions;
    this.activityExecutor = activityExecutor;
    this.assertReadOnly = assertReadOnly;
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
    ActivityStub stub = ActivityStubImpl.newInstance(merged, activityExecutor, assertReadOnly);
    function =
        (a) -> stub.execute(activityName, method.getReturnType(), method.getGenericReturnType(), a);
    return function;
  }

  @Override
  protected String proxyToString() {
    return "ActivityProxy{" + "options=" + options + '}';
  }
}
