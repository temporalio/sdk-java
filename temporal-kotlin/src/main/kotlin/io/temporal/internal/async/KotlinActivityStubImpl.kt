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

package io.temporal.internal.async

import io.temporal.activity.ActivityOptions
import io.temporal.common.interceptors.Header
import io.temporal.kotlin.interceptors.WorkflowOutboundCallsInterceptor
import java.lang.reflect.Type

internal class KotlinActivityStubImpl(
  options: ActivityOptions?,
  private val activityExecutor: WorkflowOutboundCallsInterceptor
) : KotlinActivityStub {

  private val options: ActivityOptions = ActivityOptions.newBuilder(options).validateAndBuildWithDefaults()

  override suspend fun <R> execute(activityName: String, resultClass: Class<R>, vararg args: Any): R? {
    return activityExecutor
      .executeActivity(
        WorkflowOutboundCallsInterceptor.ActivityInput(
          activityName,
          resultClass,
          resultClass,
          args,
          options,
          Header.empty()
        )
      ).result
  }

  override suspend fun <R> execute(
    activityName: String,
    resultClass: Class<R>,
    resultType: Type,
    vararg args: Any
  ): R? {
    return activityExecutor
      .executeActivity(
        WorkflowOutboundCallsInterceptor.ActivityInput(
          activityName,
          resultClass,
          resultType,
          args,
          options,
          Header.empty()
        )
      ).result
  }
}
