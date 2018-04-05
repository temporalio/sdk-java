/*
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

package com.uber.cadence.internal.sync;

import com.google.common.base.Defaults;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.workflow.ActivityException;
import com.uber.cadence.workflow.ActivityStub;
import com.uber.cadence.workflow.Promise;

/** Supports calling activity by name and arguments without its strongly typed interface. */
class ActivityStubImpl implements ActivityStub {

  private final ActivityOptions options;
  private final ActivityExecutor activityExecutor;

  static ActivityStub newInstance(ActivityOptions options, ActivityExecutor activityExecutor) {
    ActivityOptions validatedOptions =
        new ActivityOptions.Builder(options).validateAndBuildWithDefaults();
    return new ActivityStubImpl(validatedOptions, activityExecutor);
  }

  private ActivityStubImpl(ActivityOptions options, ActivityExecutor activityExecutor) {
    this.options = options;
    this.activityExecutor = activityExecutor;
  }

  @Override
  public <T> T execute(String activityName, Class<T> returnType, Object... args) {
    Promise<T> result = executeAsync(activityName, returnType, args);
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(result);
      return Defaults.defaultValue(returnType);
    }
    try {
      return result.get();
    } catch (ActivityException e) {
      // Reset stack to the current one. Otherwise it is very confusing to see a stack of
      // an event handling method.
      StackTraceElement[] currentStackTrace = Thread.currentThread().getStackTrace();
      e.setStackTrace(currentStackTrace);
      throw e;
    }
  }

  @Override
  public <R> Promise<R> executeAsync(String activityName, Class<R> returnType, Object... args) {
    return activityExecutor.executeActivity(activityName, options, args, returnType);
  }
}
