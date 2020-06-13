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

import com.google.common.base.Defaults;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Promise;
import java.lang.reflect.Type;

/** Supports calling activity by name and arguments without its strongly typed interface. */
abstract class ActivityStubBase implements ActivityStub {

  @Override
  public <T> T execute(String activityName, Class<T> resultClass, Object... args) {
    return execute(activityName, resultClass, resultClass, args);
  }

  @Override
  public <T> T execute(String activityName, Class<T> resultClass, Type resultType, Object... args) {
    Promise<T> result = executeAsync(activityName, resultClass, resultType, args);
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(result);
      return Defaults.defaultValue(resultClass);
    }
    try {
      return result.get();
    } catch (ActivityFailure e) {
      // Reset stack to the current one. Otherwise it is very confusing to see a stack of
      // an event handling method.
      StackTraceElement[] currentStackTrace = Thread.currentThread().getStackTrace();
      e.setStackTrace(currentStackTrace);
      throw e;
    }
  }

  @Override
  public <R> Promise<R> executeAsync(String activityName, Class<R> resultClass, Object... args) {
    return executeAsync(activityName, resultClass, resultClass, args);
  }

  @Override
  public abstract <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Object... args);
}
