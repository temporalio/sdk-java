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

import io.temporal.activity.ActivityOptions;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import java.lang.reflect.Type;

final class ActivityStubImpl extends ActivityStubBase {
  protected final ActivityOptions options;
  private final WorkflowOutboundCallsInterceptor activityExecutor;
  private final Functions.Proc assertReadOnly;

  static ActivityStub newInstance(
      ActivityOptions options,
      WorkflowOutboundCallsInterceptor activityExecutor,
      Functions.Proc assertReadOnly) {
    ActivityOptions validatedOptions =
        ActivityOptions.newBuilder(options).validateAndBuildWithDefaults();
    return new ActivityStubImpl(validatedOptions, activityExecutor, assertReadOnly);
  }

  ActivityStubImpl(
      ActivityOptions options,
      WorkflowOutboundCallsInterceptor activityExecutor,
      Functions.Proc assertReadOnly) {
    this.options = options;
    this.activityExecutor = activityExecutor;
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  public <R> Promise<R> executeAsync(
      String activityName, Class<R> resultClass, Type resultType, Object... args) {
    this.assertReadOnly.apply();
    return activityExecutor
        .executeActivity(
            new WorkflowOutboundCallsInterceptor.ActivityInput<>(
                activityName, resultClass, resultType, args, options, Header.empty()))
        .getResult();
  }
}
