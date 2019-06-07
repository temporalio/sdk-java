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

import com.uber.cadence.activity.LocalActivityOptions;
import com.uber.cadence.workflow.ActivityStub;
import com.uber.cadence.workflow.Promise;
import com.uber.cadence.workflow.WorkflowInterceptor;
import java.lang.reflect.Type;

public class LocalActivityStubImpl extends ActivityStubBase {
  protected final LocalActivityOptions options;
  private final WorkflowInterceptor activityExecutor;

  static ActivityStub newInstance(
      LocalActivityOptions options, WorkflowInterceptor activityExecutor) {
    LocalActivityOptions validatedOptions =
        new LocalActivityOptions.Builder(options).validateAndBuildWithDefaults();
    return new LocalActivityStubImpl(validatedOptions, activityExecutor);
  }

  private LocalActivityStubImpl(
      LocalActivityOptions options, WorkflowInterceptor activityExecutor) {
    this.options = options;
    this.activityExecutor = activityExecutor;
  }

  @Override
  public <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Object... args) {
    return activityExecutor.executeLocalActivity(
        activityName, resultClass, resultType, args, options);
  }
}
