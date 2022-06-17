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

package io.temporal.internal.activity;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.DynamicActivity;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

abstract class RootActivityInboundCallsInterceptor implements ActivityInboundCallsInterceptor {
  private ActivityExecutionContext context;

  @Override
  public void init(ActivityExecutionContext context) {
    this.context = context;
  }

  @Override
  public ActivityOutput execute(ActivityInput input) {
    CurrentActivityExecutionContext.set(context);
    try {
      Object result = executeActivity(input);
      return new ActivityOutput(result);
    } finally {
      CurrentActivityExecutionContext.unset();
    }
  }

  protected abstract Object executeActivity(ActivityInput input);

  static class POJOActivityInboundCallsInterceptor extends RootActivityInboundCallsInterceptor {
    private final Object activity;
    private final Method method;

    POJOActivityInboundCallsInterceptor(Object activity, Method method) {
      this.activity = activity;
      this.method = method;
    }

    @Override
    protected Object executeActivity(ActivityInput input) {
      try {
        return method.invoke(activity, input.getArguments());
      } catch (InvocationTargetException e) {
        throw Activity.wrap(e.getTargetException());
      } catch (Exception e) {
        throw Activity.wrap(e);
      }
    }
  }

  static class DynamicActivityInboundCallsInterceptor extends RootActivityInboundCallsInterceptor {
    private final DynamicActivity activity;

    DynamicActivityInboundCallsInterceptor(DynamicActivity activity) {
      this.activity = activity;
    }

    @Override
    protected Object executeActivity(ActivityInput input) {
      try {
        return activity.execute((EncodedValues) input.getArguments()[0]);
      } catch (Exception e) {
        throw Activity.wrap(e);
      }
    }
  }
}
