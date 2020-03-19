/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Modifications copyright (C) 2020 Temporal Technologies, Inc.
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

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Promise;
import io.temporal.workflow.WorkflowInterceptor;
import java.lang.reflect.Type;

public class ActivityStubImpl extends ActivityStubBase {
  protected final ActivityOptions options;
  private final WorkflowInterceptor activityExecutor;

  static ActivityStub newInstance(ActivityOptions options, WorkflowInterceptor activityExecutor) {
    ActivityOptions validatedOptions =
        ActivityOptions.newBuilder(options).validateAndBuildWithDefaults();
    return new ActivityStubImpl(validatedOptions, activityExecutor);
  }

  ActivityStubImpl(ActivityOptions options, WorkflowInterceptor activityExecutor) {
    this.options = options;
    this.activityExecutor = activityExecutor;
  }

  @Override
  public <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Object... args) {
    return activityExecutor.executeActivity(activityName, resultClass, resultType, args, options);
  }
}
